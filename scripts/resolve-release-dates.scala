#!/usr/bin/env -S scala-cli shebang
//> using scala "3"
//> using dep "org.typelevel::cats-effect:3.6.3"
//> using dep "com.lihaoyi::upickle:3.0.0"
//> using options -Wunused:all -deprecation

import cats.effect.IO
import cats.effect.std.Semaphore
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration as JDuration
import java.time.Instant
import java.time.OffsetDateTime

import scala.concurrent.duration.*
import scala.util.Try

object ReleaseDatesResolver:
  final val DefaultBuildConfig = Path.of(".github/workflows/buildConfig.json")
  final val DefaultOutputPath = Path.of("out/project-release-dates.json")
  final val UserAgent = "community-build3-release-dates"
  final val GitHubApiBase = "https://api.github.com"
  final val ScaladexApiBase = "https://index.scala-lang.org/api"

  final case class CliConfig(
      buildConfigPath: Path = DefaultBuildConfig,
      outputPath: Path = DefaultOutputPath,
      parallelism: Int = 16,
      scaladexParallelism: Int = 16,
      githubParallelism: Int = 4
  )

  final case class ProjectEntry(
      project: String,
      repoUrl: String,
      revision: Option[String],
      version: String,
      targets: List[ArtifactTarget]
  )

  final case class ArtifactTarget(groupId: String, artifactId: String)

  final case class GitHubRepo(owner: String, repo: String)

  final case class ReleaseResolution(
      version: String,
      revision: Option[String],
      resolvedRevision: Option[String],
      date: Option[String],
      source: String,
      error: Option[String]
  ):
    def asJson: ujson.Obj =
      ujson.Obj(
        "version" -> version,
        "revision" -> revision.map(ujson.Str(_)).getOrElse(ujson.Null),
        "resolvedRevision" -> resolvedRevision.map(ujson.Str(_)).getOrElse(ujson.Null),
        "date" -> date.map(ujson.Str(_)).getOrElse(ujson.Null),
        "source" -> source,
        "error" -> error.map(ujson.Str(_)).getOrElse(ujson.Null)
      )

  enum Service:
    case Scaladex, GitHub

  final case class Resolver(
      httpClient: HttpClient,
      githubToken: Option[String],
      scaladexLimiter: Semaphore[IO],
      githubLimiter: Semaphore[IO]
  ):
    def limiter(service: Service): Semaphore[IO] = service match
      case Service.Scaladex => scaladexLimiter
      case Service.GitHub   => githubLimiter

  final case class RetryableRequestException(message: String) extends RuntimeException(message)
  final case class GitHubRateLimitException(message: String) extends RuntimeException(message)

  def run(args: List[String]): IO[Unit] =
    for
      cliConfig <- IO.fromEither(parseArgs(args).leftMap(new IllegalArgumentException(_)))
      _ <- ensureReadable(cliConfig.buildConfigPath)
      projects <- loadBuildConfig(cliConfig.buildConfigPath)
      githubToken = sys.env.get("GITHUB_TOKEN").filter(_.nonEmpty)
      _ <- IO.println(
        s"Resolving release dates for ${projects.size} projects using ${cliConfig.parallelism} workers"
      )
      _ <- IO.whenA(githubToken.isEmpty) {
        IO.println(
          "GITHUB_TOKEN is not set. Scaladex is used first, but GitHub fallback may hit API rate limits."
        )
      }
      httpClient <- IO(buildHttpClient())
      scaladexLimiter <- Semaphore[IO](cliConfig.scaladexParallelism.toLong)
      githubLimiter <- Semaphore[IO](cliConfig.githubParallelism.toLong)
      resolver = Resolver(httpClient, githubToken, scaladexLimiter, githubLimiter)
      results <- parTraverseN(cliConfig.parallelism, projects) { case (projectName, entry) =>
        resolveProject(entry, resolver)
          .handleError { err =>
            ReleaseResolution(
              version = entry.version,
              revision = entry.revision,
              resolvedRevision = None,
              date = None,
              source = "unresolved",
              error = Some(err.getMessage)
            )
          }
          .map(projectName -> _)
      }
      sortedResults = results.sortBy(_._1)
      outputJson = ujson.Obj.from(sortedResults.map { case (projectName, resolution) =>
        projectName -> resolution.asJson
      })
      _ <- writeOutput(cliConfig.outputPath, outputJson)
      _ <- printSummary(sortedResults, cliConfig.outputPath)
    yield ()

  private def parseArgs(args: List[String]): Either[String, CliConfig] =
    args.foldLeft[Either[String, CliConfig]](Right(CliConfig())) {
      case (_, "--help" | "-h") =>
        Left(usage)
      case (left @ Left(_), _) =>
        left
      case (Right(config), s"--build-config=$path") =>
        Right(config.copy(buildConfigPath = Path.of(path)))
      case (Right(config), s"--output=$path") =>
        Right(config.copy(outputPath = Path.of(path)))
      case (Right(config), s"--parallelism=$value") =>
        parsePositiveInt("--parallelism", value).map(v => config.copy(parallelism = v))
      case (Right(config), s"--scaladex-parallelism=$value") =>
        parsePositiveInt("--scaladex-parallelism", value).map(v =>
          config.copy(scaladexParallelism = v)
        )
      case (Right(config), s"--github-parallelism=$value") =>
        parsePositiveInt("--github-parallelism", value).map(v =>
          config.copy(githubParallelism = v)
        )
      case (_, unknown) =>
        Left(s"Unknown argument: $unknown\n\n$usage")
    }

  private def parsePositiveInt(flag: String, value: String): Either[String, Int] =
    Try(value.toInt).toOption.filter(_ > 0).toRight(s"$flag must be a positive integer, got: $value")

  private def usage: String =
    """Usage: scripts/resolve-release-dates.scala [options]
      |
      |Options:
      |  --build-config=<path>           Path to buildConfig.json
      |  --output=<path>                 Output JSON path, defaults to out/project-release-dates.json
      |  --parallelism=<n>               Max concurrent project resolutions, defaults to 16
      |  --scaladex-parallelism=<n>      Max concurrent Scaladex requests, defaults to 16
      |  --github-parallelism=<n>        Max concurrent GitHub requests, defaults to 4
      |""".stripMargin

  private def ensureReadable(path: Path): IO[Unit] =
    IO.blocking(Files.isRegularFile(path)).flatMap { exists =>
      if exists then IO.unit
      else IO.raiseError(new IllegalArgumentException(s"Config file not found: $path"))
    }

  private def loadBuildConfig(path: Path): IO[List[(String, ProjectEntry)]] =
    IO.blocking {
      val json = ujson.read(Files.readString(path))
      json.obj.toList.map { case (projectName, value) =>
        projectName -> parseProjectEntry(projectName, value)
      }
    }

  private def parseProjectEntry(projectName: String, value: ujson.Value): ProjectEntry =
    val obj = value.obj
    ProjectEntry(
      project = projectName,
      repoUrl = obj("repoUrl").str.trim,
      revision = obj.value.get("revision").collect { case ujson.Str(v) if v.trim.nonEmpty => v.trim },
      version = obj("version").str.trim,
      targets = obj.value
        .get("targets")
        .collect { case ujson.Str(targets) => parseTargets(targets) }
        .getOrElse(Nil)
    )

  private def parseTargets(targets: String): List[ArtifactTarget] =
    targets
      .trim
      .split("\\s+")
      .toList
      .filter(_.nonEmpty)
      .flatMap { token =>
        token.split("%", 2).toList match
          case groupId :: artifactId :: Nil if groupId.nonEmpty && artifactId.nonEmpty =>
            Some(ArtifactTarget(groupId = groupId, artifactId = artifactId))
          case _ =>
            None
      }
      .distinct

  private def buildHttpClient(): HttpClient =
    HttpClient
      .newBuilder()
      .connectTimeout(JDuration.ofSeconds(20))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build()

  private def parTraverseN[A, B](parallelism: Int, values: List[A])(
      f: A => IO[B]
  ): IO[List[B]] =
    Semaphore[IO](parallelism.toLong).flatMap { limiter =>
      values.parTraverse(value => limiter.permit.use(_ => f(value)))
    }

  private def resolveProject(entry: ProjectEntry, resolver: Resolver): IO[ReleaseResolution] =
    parseGitHubRepo(entry.repoUrl) match
      case Some(repo) =>
        val revision = entry.revision.map(_.trim).filter(_.nonEmpty)
        revision match
          case None =>
            resolveWithoutRevision(repo, entry, resolver)
          case Some(rev) if isHeadLike(rev) =>
            resolveHeadLike(repo, entry, resolver)
          case Some(rev) =>
            resolveWithRevision(repo, entry, rev, resolver)
      case None if hasConcreteVersion(entry.version) =>
        resolveViaScaladex(entry, resolver).map {
          case Some(resolution) => resolution
          case None =>
            ReleaseResolution(
              version = entry.version,
              revision = entry.revision,
              resolvedRevision = None,
              date = None,
              source = "unresolved",
              error = Some(s"Unsupported repository URL: ${entry.repoUrl}")
            )
        }
      case None =>
        IO.pure(
          ReleaseResolution(
            version = entry.version,
            revision = entry.revision,
            resolvedRevision = None,
            date = None,
            source = "unresolved",
            error = Some(s"Unsupported repository URL: ${entry.repoUrl}")
          )
        )

  private def resolveWithoutRevision(
      repo: GitHubRepo,
      entry: ProjectEntry,
      resolver: Resolver
  ): IO[ReleaseResolution] =
    orElse(resolveViaScaladex(entry, resolver))(resolveTagCandidates(repo, entry, resolver))
      .flatMap {
        case Some(resolution) => IO.pure(resolution)
        case None => resolveDefaultBranch(repo, entry, resolver)
      }

  private def resolveHeadLike(
      repo: GitHubRepo,
      entry: ProjectEntry,
      resolver: Resolver
  ): IO[ReleaseResolution] =
    resolveViaScaladex(entry, resolver).flatMap {
      case Some(resolution) => IO.pure(resolution)
      case None => resolveDefaultBranch(repo, entry, resolver)
    }

  private def resolveWithRevision(
      repo: GitHubRepo,
      entry: ProjectEntry,
      revision: String,
      resolver: Resolver
  ): IO[ReleaseResolution] =
    val earlyBranchResolution =
      if isLikelyBranchName(revision) then resolveExactBranch(repo, entry, revision, resolver)
      else IO.pure(None)

    orElse(resolveExactTag(repo, entry, revision, resolver))(earlyBranchResolution)
      .flatMap(resolution => orElse(IO.pure(resolution))(resolveViaScaladex(entry, resolver)))
      .flatMap(resolution =>
        orElse(IO.pure(resolution))(resolveTagCandidates(repo, entry, resolver, skip = Set(revision)))
      )
      .flatMap(resolution => orElse(IO.pure(resolution))(resolveExactBranch(repo, entry, revision, resolver)))
      .flatMap(resolution => orElse(IO.pure(resolution))(resolveExactCommit(repo, entry, revision, resolver)))
      .map {
        case Some(resolution) => resolution
        case None =>
          ReleaseResolution(
            version = entry.version,
            revision = entry.revision,
            resolvedRevision = None,
            date = None,
            source = "unresolved",
            error = Some(s"Could not resolve release date for revision $revision")
          )
      }

  private def resolveViaScaladex(
      entry: ProjectEntry,
      resolver: Resolver
  ): IO[Option[ReleaseResolution]] =
    if hasConcreteVersion(entry.version) then
      val directCandidates = entry.targets.take(4)
      tryScaladexArtifactTargets(entry, directCandidates, resolver)
        .flatMap {
          case some @ Some(_) => IO.pure(some)
          case None => resolveViaScaladexProjectArtifacts(entry, resolver)
        }
    else IO.pure(None)

  private def tryScaladexArtifactTargets(
      entry: ProjectEntry,
      targets: List[ArtifactTarget],
      resolver: Resolver
  ): IO[Option[ReleaseResolution]] =
    targets match
      case Nil => IO.pure(None)
      case target :: tail =>
        scaladexArtifactRelease(entry, target, entry.version, resolver).flatMap {
          case some @ Some(_) => IO.pure(some)
          case None => tryScaladexArtifactTargets(entry, tail, resolver)
        }

  private def resolveViaScaladexProjectArtifacts(
      entry: ProjectEntry,
      resolver: Resolver
  ): IO[Option[ReleaseResolution]] =
    parseGitHubRepo(entry.repoUrl)
      .traverse { repo =>
        val uri = toUri(
          s"$ScaladexApiBase/projects/${encodePathSegment(repo.owner)}/${encodePathSegment(repo.repo)}/artifacts?stable-only=false"
        )
        getJson(uri, Service.Scaladex, resolver).flatMap {
          case Some(json) =>
            val matchingArtifact = json.arr.collectFirst {
              case artifact
                  if artifact.obj.value.get("version").collect { case ujson.Str(v) => v }.contains(entry.version) =>
                ArtifactTarget(
                  groupId = artifact("groupId").str,
                  artifactId = artifact("artifactId").str
                )
            }
            matchingArtifact
              .traverse(target => scaladexArtifactRelease(entry, target, entry.version, resolver))
              .map(_.flatten)
          case None =>
            IO.pure(None)
        }
      }
      .map(_.flatten)

  private def scaladexArtifactRelease(
      entry: ProjectEntry,
      target: ArtifactTarget,
      version: String,
      resolver: Resolver
  ): IO[Option[ReleaseResolution]] =
    val uri = toUri(
      s"$ScaladexApiBase/artifacts/${encodePathSegment(target.groupId)}/${encodePathSegment(target.artifactId)}/${encodePathSegment(version)}"
    )
    getJson(uri, Service.Scaladex, resolver).map {
      _.flatMap { json =>
        json.obj.value
          .get("releaseDate")
          .collect { case ujson.Str(value) =>
            ReleaseResolution(
              version = version,
              revision = entry.revision,
              resolvedRevision = Some(s"${target.groupId}:${target.artifactId}:$version"),
              date = Some(OffsetDateTime.parse(value).toInstant.toString),
              source = "scaladex",
              error = None
            )
          }
      }
    }

  private def resolveTagCandidates(
      repo: GitHubRepo,
      entry: ProjectEntry,
      resolver: Resolver,
      skip: Set[String] = Set.empty
  ): IO[Option[ReleaseResolution]] =
    tagCandidates(entry)
      .filterNot(skip.contains)
      .distinct match
      case Nil => IO.pure(None)
      case tags =>
        resolveFirst(tags) { tag =>
          resolveExactTag(repo, entry, tag, resolver)
        }

  private def tagCandidates(entry: ProjectEntry): List[String] =
    val versionCandidates =
      Option.when(hasConcreteVersion(entry.version))(entry.version).toList.flatMap { version =>
        val prefixed = if version.startsWith("v") then Nil else List(s"v$version")
        val stripped = if version.startsWith("v") && version.length > 1 then List(version.drop(1)) else Nil
        version :: prefixed ::: stripped
      }
    val revisionCandidates =
      entry.revision.toList.flatMap { revision =>
        val strippedRef = revision.stripPrefix("refs/tags/")
        val normalized = strippedRef :: Nil
        val prefixed =
          if looksLikeVersionish(strippedRef) && !strippedRef.startsWith("v") then List(s"v$strippedRef")
          else Nil
        val stripped =
          if strippedRef.startsWith("v") && strippedRef.length > 1 then List(strippedRef.drop(1))
          else Nil
        normalized ::: prefixed ::: stripped
      }
    (revisionCandidates ::: versionCandidates).filterNot(isHeadLike).filter(_.nonEmpty)

  private def resolveFirst[A, B](values: List[A])(f: A => IO[Option[B]]): IO[Option[B]] =
    values match
      case Nil => IO.pure(None)
      case head :: tail =>
        f(head).flatMap {
          case some @ Some(_) => IO.pure(some)
          case None           => resolveFirst(tail)(f)
        }

  private def orElse[A](current: IO[Option[A]])(fallback: => IO[Option[A]]): IO[Option[A]] =
    current.flatMap {
      case some @ Some(_) => IO.pure(some)
      case None           => fallback
    }

  private def resolveExactTag(
      repo: GitHubRepo,
      entry: ProjectEntry,
      revision: String,
      resolver: Resolver
  ): IO[Option[ReleaseResolution]] =
    val tagRef = revision.stripPrefix("refs/tags/")
    getGitRef(repo, s"tags/$tagRef", resolver).flatMap {
      case Some(refJson) =>
        val refObject = refJson("object")
        val objectType = refObject("type").str
        val sha = refObject("sha").str
        objectType match
          case "tag" =>
            getGitTag(repo, sha, resolver).flatMap {
              case Some(tagJson) =>
                val commitSha =
                  tagJson.obj.value
                    .get("object")
                    .flatMap(_.obj.value.get("sha"))
                    .collect { case ujson.Str(value) => value }
                val date =
                  tagJson.obj.value
                    .get("tagger")
                    .flatMap(_.obj.value.get("date"))
                    .collect { case ujson.Str(value) => value }
                date match
                  case Some(value) =>
                    IO.pure(
                      Some(
                        ReleaseResolution(
                          version = entry.version,
                          revision = entry.revision,
                          resolvedRevision = Some(tagRef),
                          date = Some(OffsetDateTime.parse(value).toInstant.toString),
                          source = "github-tag",
                          error = None
                        )
                      )
                    )
                  case None =>
                    commitSha
                      .traverse(getCommit(repo, _, resolver))
                      .map(_.flatten)
                      .map {
                      _.map { instant =>
                        ReleaseResolution(
                          version = entry.version,
                          revision = entry.revision,
                          resolvedRevision = Some(tagRef),
                          date = Some(instant.toString),
                          source = "github-tag",
                          error = None
                        )
                      }
                    }
              case None =>
                IO.pure(None)
            }
          case "commit" =>
            getCommit(repo, sha, resolver).map {
              _.map { instant =>
                ReleaseResolution(
                  version = entry.version,
                  revision = entry.revision,
                  resolvedRevision = Some(tagRef),
                  date = Some(instant.toString),
                  source = "github-tag",
                  error = None
                )
              }
            }
          case _ =>
            IO.pure(None)
      case None =>
        IO.pure(None)
    }

  private def resolveExactBranch(
      repo: GitHubRepo,
      entry: ProjectEntry,
      revision: String,
      resolver: Resolver
  ): IO[Option[ReleaseResolution]] =
    val branch = revision.stripPrefix("refs/heads/").stripPrefix("origin/")
    getCommit(repo, branch, resolver).map {
      _.map { instant =>
        ReleaseResolution(
          version = entry.version,
          revision = entry.revision,
          resolvedRevision = Some(branch),
          date = Some(instant.toString),
          source = "github-branch",
          error = None
        )
      }
    }

  private def resolveExactCommit(
      repo: GitHubRepo,
      entry: ProjectEntry,
      revision: String,
      resolver: Resolver
  ): IO[Option[ReleaseResolution]] =
    getCommit(repo, revision, resolver).map {
      _.map { instant =>
        ReleaseResolution(
          version = entry.version,
          revision = entry.revision,
          resolvedRevision = Some(revision),
          date = Some(instant.toString),
          source = "github-commit",
          error = None
        )
      }
    }

  private def resolveDefaultBranch(
      repo: GitHubRepo,
      entry: ProjectEntry,
      resolver: Resolver
  ): IO[ReleaseResolution] =
    getRepoInfo(repo, resolver).flatMap {
      case Some(defaultBranch) =>
        getCommit(repo, defaultBranch, resolver).map {
          case Some(instant) =>
            ReleaseResolution(
              version = entry.version,
              revision = entry.revision,
              resolvedRevision = Some(defaultBranch),
              date = Some(instant.toString),
              source = "github-default-branch",
              error = None
            )
          case None =>
            ReleaseResolution(
              version = entry.version,
              revision = entry.revision,
              resolvedRevision = Some(defaultBranch),
              date = None,
              source = "unresolved",
              error = Some(s"Could not resolve tip commit for default branch $defaultBranch")
            )
        }
      case None =>
        IO.pure(
          ReleaseResolution(
            version = entry.version,
            revision = entry.revision,
            resolvedRevision = None,
            date = None,
            source = "unresolved",
            error = Some("Could not resolve repository default branch")
          )
        )
    }

  private def getRepoInfo(repo: GitHubRepo, resolver: Resolver): IO[Option[String]] =
    val uri = toUri(
      s"$GitHubApiBase/repos/${encodePathSegment(repo.owner)}/${encodePathSegment(repo.repo)}"
    )
    getJson(uri, Service.GitHub, resolver).map {
      _.flatMap(
        _.obj.value.get("default_branch").collect { case ujson.Str(branch) => branch }
      )
    }

  private def getGitRef(repo: GitHubRepo, ref: String, resolver: Resolver): IO[Option[ujson.Value]] =
    val uri = toUri(
      s"$GitHubApiBase/repos/${encodePathSegment(repo.owner)}/${encodePathSegment(repo.repo)}/git/ref/${encodePathSegment(ref)}"
    )
    getJson(uri, Service.GitHub, resolver, missingStatuses = Set(404, 422))

  private def getGitTag(repo: GitHubRepo, sha: String, resolver: Resolver): IO[Option[ujson.Value]] =
    val uri = toUri(
      s"$GitHubApiBase/repos/${encodePathSegment(repo.owner)}/${encodePathSegment(repo.repo)}/git/tags/${encodePathSegment(sha)}"
    )
    getJson(uri, Service.GitHub, resolver)

  private def getCommit(
      repo: GitHubRepo,
      ref: String,
      resolver: Resolver
  ): IO[Option[Instant]] =
    val uri = toUri(
      s"$GitHubApiBase/repos/${encodePathSegment(repo.owner)}/${encodePathSegment(repo.repo)}/commits/${encodePathSegment(ref)}"
    )
    getJson(uri, Service.GitHub, resolver, missingStatuses = Set(404, 422)).map {
      _.flatMap { json =>
        val commitObj = json("commit").obj
        val rawDate =
          commitObj.value
            .get("committer")
            .flatMap(_.obj.value.get("date"))
            .collect { case ujson.Str(value) => value }
            .orElse(
              commitObj.value
                .get("author")
                .flatMap(_.obj.value.get("date"))
                .collect { case ujson.Str(value) => value }
            )
        rawDate.map(OffsetDateTime.parse(_).toInstant)
      }
    }

  private def getJson(
      uri: URI,
      service: Service,
      resolver: Resolver,
      missingStatuses: Set[Int] = Set(404)
  ): IO[Option[ujson.Value]] =
    requestJson(uri, service, resolver, missingStatuses, attempt = 1, maxAttempts = 4)

  private def requestJson(
      uri: URI,
      service: Service,
      resolver: Resolver,
      missingStatuses: Set[Int],
      attempt: Int,
      maxAttempts: Int
  ): IO[Option[ujson.Value]] =
    val headers = service match
      case Service.GitHub =>
        Map(
          "Accept" -> "application/vnd.github+json",
          "X-GitHub-Api-Version" -> "2022-11-28"
        ) ++ resolver.githubToken.map(token => "Authorization" -> s"Bearer $token")
      case Service.Scaladex =>
        Map.empty[String, String]

    val sendRequest =
      resolver.limiter(service).permit.use { _ =>
        IO.blocking {
          val requestBuilder = HttpRequest
            .newBuilder(uri)
            .timeout(JDuration.ofSeconds(30))
            .header("User-Agent", UserAgent)
          headers.foreach { case (name, value) => requestBuilder.header(name, value) }
          val request = requestBuilder.GET().build()
          resolver.httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        }
      }

    sendRequest.flatMap { response =>
      response.statusCode match
        case 200 =>
          IO.delay(ujson.read(response.body)).map(Some(_)).handleErrorWith { err =>
            retryOrFail(
              service = service,
              uri = uri,
              attempt = attempt,
              maxAttempts = maxAttempts,
              err = RetryableRequestException(
                s"Failed to decode JSON from $uri: ${err.getMessage}"
              ),
              next = requestJson(uri, service, resolver, missingStatuses, attempt + 1, maxAttempts)
            )
          }
        case status if missingStatuses.contains(status) =>
          IO.pure(None)
        case 403 if service == Service.GitHub && isRateLimited(response) =>
          IO.raiseError(
            GitHubRateLimitException(githubRateLimitMessage(response))
          )
        case status if retryableStatus(status) =>
          retryOrFail(
            service = service,
            uri = uri,
            attempt = attempt,
            maxAttempts = maxAttempts,
            err = RetryableRequestException(
              s"HTTP $status from $uri: ${truncate(response.body)}"
            ),
            next = requestJson(uri, service, resolver, missingStatuses, attempt + 1, maxAttempts)
          )
        case status =>
          IO.raiseError(
            RuntimeException(s"HTTP $status from $uri: ${truncate(response.body)}")
          )
    }.handleErrorWith { err =>
      err match
        case _: GitHubRateLimitException =>
          IO.raiseError(err)
        case _: RetryableRequestException =>
          IO.raiseError(err)
        case other if attempt < maxAttempts =>
          retryOrFail(
            service = service,
            uri = uri,
            attempt = attempt,
            maxAttempts = maxAttempts,
            err = RetryableRequestException(other.getMessage),
            next = requestJson(uri, service, resolver, missingStatuses, attempt + 1, maxAttempts)
          )
        case other =>
          IO.raiseError(other)
    }

  private def retryOrFail(
      service: Service,
      uri: URI,
      attempt: Int,
      maxAttempts: Int,
      err: Throwable,
      next: => IO[Option[ujson.Value]]
  ): IO[Option[ujson.Value]] =
    if attempt >= maxAttempts then IO.raiseError(err)
    else
      val delay = (math.pow(2, attempt.toDouble - 1).toLong max 1L).seconds
      IO.println(
        s"Retrying ${service.toString.toLowerCase} request to $uri after $delay because: ${err.getMessage}"
      ) *> IO.sleep(delay) *> next

  private def retryableStatus(status: Int): Boolean =
    status == 408 || status == 409 || status == 425 || status == 429 || status >= 500

  private def isRateLimited(response: HttpResponse[String]): Boolean =
    Option(response.headers.firstValue("x-ratelimit-remaining").orElse(null)).contains("0")

  private def githubRateLimitMessage(response: HttpResponse[String]): String =
    val resetAt = Option(response.headers.firstValue("x-ratelimit-reset").orElse(null))
      .flatMap(value => Try(value.toLong).toOption)
      .map(Instant.ofEpochSecond)
      .map(_.toString)
      .getOrElse("unknown")
    s"GitHub API rate limit reached. Reset at $resetAt. Set GITHUB_TOKEN to raise the limit."

  private def truncate(value: String, maxLength: Int = 200): String =
    if value.length <= maxLength then value
    else value.take(maxLength) + "..."

  private def parseGitHubRepo(repoUrl: String): Option[GitHubRepo] =
    val normalized = repoUrl.trim.stripSuffix("/")
    val httpsPrefix = "https://github.com/"
    val sshPrefix = "git@github.com:"
    val rawCoordinates =
      if normalized.startsWith(httpsPrefix) then Some(normalized.stripPrefix(httpsPrefix))
      else if normalized.startsWith(sshPrefix) then Some(normalized.stripPrefix(sshPrefix))
      else None

    rawCoordinates.flatMap { coordinates =>
      coordinates.stripSuffix(".git").split("/", 2).toList match
        case owner :: repo :: Nil if owner.nonEmpty && repo.nonEmpty =>
          Some(GitHubRepo(owner, repo))
        case _ =>
          None
    }

  private def hasConcreteVersion(version: String): Boolean =
    version.trim.nonEmpty && !isHeadLike(version)

  private def isHeadLike(value: String): Boolean =
    value.trim.equalsIgnoreCase("HEAD")

  private def isLikelyBranchName(value: String): Boolean =
    val trimmed = value.trim
    trimmed.startsWith("refs/heads/") ||
    trimmed == "main" ||
    trimmed == "master" ||
    trimmed == "develop" ||
    trimmed == "dev" ||
    trimmed == "trunk" ||
    trimmed.startsWith("origin/") ||
    (trimmed.contains("/") && !looksLikeVersionish(trimmed))

  private def looksLikeVersionish(value: String): Boolean =
    value.nonEmpty && (value.head.isDigit || value.startsWith("v") || value.contains('.'))

  private def encodePathSegment(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

  private def toUri(value: String): URI =
    URI.create(value)

  private def writeOutput(path: Path, json: ujson.Value): IO[Unit] =
    IO.blocking {
      Option(path.getParent).foreach(parent => Files.createDirectories(parent))
      Files.writeString(path, ujson.write(json, indent = 2) + "\n")
    }

  private def printSummary(
      results: List[(String, ReleaseResolution)],
      outputPath: Path
  ): IO[Unit] =
    val resolved = results.count(_._2.date.nonEmpty)
    val unresolved = results.size - resolved
    val bySource = results.groupMapReduce(_._2.source)(_ => 1)(_ + _).toList.sortBy(_._1)
    val sourcesSummary =
      if bySource.isEmpty then "none"
      else bySource.map { case (source, count) => s"$source=$count" }.mkString(", ")
    IO.println(
      s"Wrote ${results.size} project release dates to $outputPath. Resolved=$resolved unresolved=$unresolved. Sources: $sourcesSummary"
    )

@main def resolveReleaseDates(args: String*): Unit =
  ReleaseDatesResolver.run(args.toList).unsafeRunSync()
