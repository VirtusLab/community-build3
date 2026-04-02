#!/usr/bin/env -S scala-cli shebang
//> using scala "3"
//> using dep "org.typelevel::cats-effect:3.6.3"
//> using dep "com.lihaoyi::upickle:3.0.0"
//> using options -Wunused:all -deprecation

import cats.effect.IO
import cats.effect.std.Semaphore
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*

import java.io.StringReader
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
import java.time.LocalDate
import java.time.ZoneOffset
import javax.xml.parsers.DocumentBuilderFactory

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource

import scala.concurrent.duration.*
import scala.util.Try
import scala.util.matching.Regex

object ScaladexEcosystemScalaMinorDistributionApp:
  private val Utc = ZoneOffset.UTC
  private val ScaladexApiBase = "https://index.scala-lang.org/api/v1"
  private val MavenCentralBase = "https://repo.maven.apache.org/maven2"
  private val DefaultContextOutputPath = Path.of("out/scaladex-project-context.json")
  private val UserAgent = "community-build3-scaladex-ecosystem"
  private val Scala2MinorPattern = """(?:^|_)(2\.\d+)(?:$|_)""".r
  private val ScalaMajorMinorPattern = raw"(\d+)\.(\d+)".r
  private val PropertyPattern: Regex = """\$\{([^}]+)\}""".r

  enum OutputFormat:
    case Table, Json

  final case class CliConfig(
      asOf: LocalDate = LocalDate.now(Utc),
      format: OutputFormat = OutputFormat.Table,
      contextOutputPath: Path = DefaultContextOutputPath,
      projectParallelism: Int = 24,
      artifactParallelism: Int = 64,
      pomParallelism: Int = 32,
      retries: Int = 2,
      limitProjects: Option[Int] = None
  )

  final case class ProjectRef(organization: String, repository: String):
    def id: String = s"$organization/$repository"

  final case class ArtifactCoordinate(groupId: String, artifactId: String, version: String):
    def pomUri: URI =
      URI.create(
        s"$MavenCentralBase/${groupId.replace('.', '/')}/${encodePathSegment(artifactId)}/${encodePathSegment(version)}/${encodePathSegment(artifactId)}-${encodePathSegment(version)}.pom"
      )

  final case class ArtifactDetail(
      coordinate: ArtifactCoordinate,
      binaryVersion: String,
      language: String,
      platform: String,
      project: ProjectRef,
      releaseDate: Instant
  )

  final case class ProjectContext(
      organization: String,
      repository: String,
      lastReleaseDate: Option[String],
      scalaMinors: List[String],
      latestArtifactCount: Int,
      resolvedArtifactCount: Int,
      scala3ArtifactCount: Int,
      artifactDetailFailures: Int,
      scala3PomFailures: Int,
      errors: List[String]
  ):
    def project: ProjectRef = ProjectRef(organization, repository)
    def lastReleaseLocalDate: Option[LocalDate] =
      lastReleaseDate.flatMap(value => Try(Instant.parse(value).atZone(Utc).toLocalDate).toOption)

    def asJson: ujson.Obj =
      ujson.Obj(
        "organization" -> organization,
        "repository" -> repository,
        "lastReleaseDate" -> lastReleaseDate.map(ujson.Str(_)).getOrElse(ujson.Null),
        "scalaMinors" -> ujson.Arr.from(scalaMinors.map(ujson.Str(_))),
        "latestArtifactCount" -> latestArtifactCount,
        "resolvedArtifactCount" -> resolvedArtifactCount,
        "scala3ArtifactCount" -> scala3ArtifactCount,
        "artifactDetailFailures" -> artifactDetailFailures,
        "scala3PomFailures" -> scala3PomFailures,
        "errors" -> ujson.Arr.from(errors.map(ujson.Str(_)))
      )

  final case class Bucket(
      id: String,
      label: String,
      description: String,
      startInclusive: Option[LocalDate],
      endInclusive: Option[LocalDate]
  ):
    def contains(date: LocalDate): Boolean =
      val afterStart = startInclusive.forall(start => !date.isBefore(start))
      val beforeEnd = endInclusive.forall(end => !date.isAfter(end))
      afterStart && beforeEnd

  final case class BucketStats(
      bucket: Bucket,
      totalProjectMinorPairs: Int,
      countsByMinor: Map[String, Int]
  )

  final case class Summary(
      totalProjectsFromScaladex: Int,
      projectsProcessed: Int,
      projectsWithLatestArtifacts: Int,
      projectsWithReleaseDate: Int,
      projectsWithScalaMinor: Int,
      projectsIncludedInDistribution: Int,
      projectsWithoutLatestArtifacts: Int,
      projectsWithoutReleaseDate: Int,
      projectsWithoutScalaMinor: Int,
      projectsWithFutureReleaseDate: Int,
      totalProjectMinorPairs: Int,
      artifactDetailsResolved: Int,
      artifactDetailFailures: Int,
      scala3ArtifactsSeen: Int,
      scala3PomsResolved: Int,
      scala3PomFailures: Int,
      projectsWithErrors: Int
  )

  final case class Report(
      config: CliConfig,
      summary: Summary,
      scalaMinors: List[String],
      overallCountsByMinor: Map[String, Int],
      buckets: List[BucketStats]
  )

  final case class Resolver(
      httpClient: HttpClient,
      retries: Int,
      artifactLimiter: Semaphore[IO],
      pomLimiter: Semaphore[IO]
  )

  final case class RequestFailedException(message: String) extends RuntimeException(message)

  def run(args: List[String]): IO[Unit] =
    if args.contains("--help") || args.contains("-h") then
      IO.println(usage)
    else
      for
        config <- IO.fromEither(parseArgs(args).leftMap(new IllegalArgumentException(_)))
        _ <- IO.println(
          s"Fetching Scaladex project index and aggregating latest-release support by Scala minor as of ${config.asOf}"
        )
        httpClient <- IO(buildHttpClient())
        artifactLimiter <- Semaphore[IO](config.artifactParallelism.toLong)
        pomLimiter <- Semaphore[IO](config.pomParallelism.toLong)
        resolver = Resolver(httpClient, config.retries, artifactLimiter, pomLimiter)
        allProjects <- fetchAllProjects(resolver)
        selectedProjects = config.limitProjects.fold(allProjects)(limit => allProjects.take(limit))
        _ <- IO.println(
          s"Loaded ${selectedProjects.size} projects from Scaladex${config.limitProjects.fold("")(limit => s" (limit=$limit, full index=${allProjects.size})")}"
        )
        contexts <- parTraverseN(config.projectParallelism, selectedProjects) { project =>
          resolveProject(project, resolver).handleError { err =>
            ProjectContext(
              organization = project.organization,
              repository = project.repository,
              lastReleaseDate = None,
              scalaMinors = Nil,
              latestArtifactCount = 0,
              resolvedArtifactCount = 0,
              scala3ArtifactCount = 0,
              artifactDetailFailures = 0,
              scala3PomFailures = 0,
              errors = List(s"project resolution failed: ${err.getMessage}")
            )
          }
        }
        sortedContexts = contexts.sortBy(context => context.project.id)
        _ <- writeContextOutput(sortedContexts, config.contextOutputPath)
        report = buildReport(sortedContexts, allProjects.size, config)
        _ <- config.format match
          case OutputFormat.Table => IO.println(renderTableReport(report))
          case OutputFormat.Json  => IO.println(renderJsonReport(report))
      yield ()

  private def parseArgs(args: List[String]): Either[String, CliConfig] =
    args.foldLeft[Either[String, CliConfig]](Right(CliConfig())) {
      case (_, "--help" | "-h") =>
        Left(usage)
      case (left @ Left(_), _) =>
        left
      case (Right(config), s"--as-of=$value") =>
        parseDate(value).map(date => config.copy(asOf = date))
      case (Right(config), "--format=table") =>
        Right(config.copy(format = OutputFormat.Table))
      case (Right(config), "--format=json") =>
        Right(config.copy(format = OutputFormat.Json))
      case (Right(config), s"--context-output=$path") =>
        Right(config.copy(contextOutputPath = Path.of(path)))
      case (Right(config), s"--project-parallelism=$value") =>
        parsePositiveInt("--project-parallelism", value).map(v => config.copy(projectParallelism = v))
      case (Right(config), s"--artifact-parallelism=$value") =>
        parsePositiveInt("--artifact-parallelism", value).map(v => config.copy(artifactParallelism = v))
      case (Right(config), s"--pom-parallelism=$value") =>
        parsePositiveInt("--pom-parallelism", value).map(v => config.copy(pomParallelism = v))
      case (Right(config), s"--retries=$value") =>
        parseNonNegativeInt("--retries", value).map(v => config.copy(retries = v))
      case (Right(config), s"--limit-projects=$value") =>
        parsePositiveInt("--limit-projects", value).map(v => config.copy(limitProjects = Some(v)))
      case (_, unknown) =>
        Left(s"Unknown argument: $unknown\n\n$usage")
    }

  private def parseDate(value: String): Either[String, LocalDate] =
    Try(LocalDate.parse(value)).toEither.leftMap(_ => s"--as-of must use YYYY-MM-DD, got: $value")

  private def parsePositiveInt(flag: String, value: String): Either[String, Int] =
    Try(value.toInt).toOption.filter(_ > 0).toRight(s"$flag must be a positive integer, got: $value")

  private def parseNonNegativeInt(flag: String, value: String): Either[String, Int] =
    Try(value.toInt).toOption.filter(_ >= 0).toRight(s"$flag must be a non-negative integer, got: $value")

  private def usage: String =
    """Usage: scripts/scaladex-ecosystem-scala-minor-distribution.scala [options]
      |
      |Options:
      |  --as-of=<YYYY-MM-DD>            Reference date for bucket boundaries, defaults to current UTC date
      |  --format=table|json             Output format, defaults to table
      |  --context-output=<path>         Where to write per-project context JSON, defaults to out/scaladex-project-context.json
      |  --project-parallelism=<n>       Concurrent project resolutions, defaults to 24
      |  --artifact-parallelism=<n>      Concurrent Scaladex artifact-detail requests, defaults to 64
      |  --pom-parallelism=<n>           Concurrent Maven POM fetches for Scala 3 artifacts, defaults to 32
      |  --retries=<n>                   Retries per request after the first attempt, defaults to 2
      |  --limit-projects=<n>            Limit project count for smoke-testing
      |
      |The report counts project/minor pairs from the latest release visible in Scaladex.
      |A project can contribute to multiple Scala minors when its latest release is cross-published.
      |Scala 2 minors are derived from Scaladex binary-version suffixes.
      |Scala 3 minors are derived from Maven POM dependencies on scala3-library_3 or scala3-compiler_3.
      |""".stripMargin

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

  private def fetchAllProjects(resolver: Resolver): IO[List[ProjectRef]] =
    getJson(
      uri = URI.create(s"$ScaladexApiBase/projects"),
      description = "Scaladex project index",
      resolver = resolver
    ).map { json =>
      json.arr.toList.map { value =>
        val obj = value.obj
        ProjectRef(
          organization = obj("organization").str,
          repository = obj("repository").str
        )
      }
    }

  private def resolveProject(project: ProjectRef, resolver: Resolver): IO[ProjectContext] =
    for
      latestCoordinates <- fetchLatestCoordinates(project, resolver)
      distinctCoordinates = latestCoordinates.distinct
      detailResults <- distinctCoordinates.parTraverse { coordinate =>
        resolver.artifactLimiter.permit.use { _ =>
          fetchArtifactDetail(coordinate, resolver).attempt
        }
      }
      resolvedDetails = detailResults.collect { case Right(detail) => detail }
      artifactFailures = detailResults.collect { case Left(_) => 1 }.sum
      scala3Details = resolvedDetails.filter(isScala3Artifact)
      scala3MinorResults <- scala3Details.parTraverse { detail =>
        resolver.pomLimiter.permit.use { _ =>
          resolveScala3Minor(detail.coordinate, resolver).attempt
        }
      }
      scala3Minors = scala3MinorResults.collect { case Right(Some(minor)) => minor }
      scala3PomFailures = scala3MinorResults.collect { case Left(_) | Right(None) => 1 }.sum
      scala2Minors = resolvedDetails.flatMap(resolveScala2Minor)
      scalaMinors = (scala2Minors ++ scala3Minors).distinct.sorted(using minorOrdering)
      errors = collectErrors(detailResults, scala3MinorResults)
      lastRelease = resolvedDetails
        .map(_.releaseDate)
        .reduceOption((left, right) => if left.isAfter(right) then left else right)
        .map(_.toString)
    yield ProjectContext(
      organization = project.organization,
      repository = project.repository,
      lastReleaseDate = lastRelease,
      scalaMinors = scalaMinors,
      latestArtifactCount = distinctCoordinates.size,
      resolvedArtifactCount = resolvedDetails.size,
      scala3ArtifactCount = scala3Details.size,
      artifactDetailFailures = artifactFailures,
      scala3PomFailures = scala3PomFailures,
      errors = errors
    )

  private def fetchLatestCoordinates(project: ProjectRef, resolver: Resolver): IO[List[ArtifactCoordinate]] =
    getJson(
      uri = URI.create(
        s"$ScaladexApiBase/projects/${encodePathSegment(project.organization)}/${encodePathSegment(project.repository)}/versions/latest"
      ),
      description = s"latest artifacts for ${project.id}",
      resolver = resolver
    ).map { json =>
      json.arr.toList.map { value =>
        val obj = value.obj
        ArtifactCoordinate(
          groupId = obj("groupId").str,
          artifactId = obj("artifactId").str,
          version = obj("version").str
        )
      }
    }

  private def fetchArtifactDetail(coordinate: ArtifactCoordinate, resolver: Resolver): IO[ArtifactDetail] =
    getJson(
      uri = URI.create(
        s"$ScaladexApiBase/artifacts/${encodePathSegment(coordinate.groupId)}/${encodePathSegment(coordinate.artifactId)}/${encodePathSegment(coordinate.version)}"
      ),
      description = s"artifact detail for ${coordinate.groupId}:${coordinate.artifactId}:${coordinate.version}",
      resolver = resolver
    ).map { json =>
      val obj = json.obj
      val projectObj = obj("project").obj
      ArtifactDetail(
        coordinate = coordinate,
        binaryVersion = obj("binaryVersion").str,
        language = obj("language").str,
        platform = obj("platform").str,
        project = ProjectRef(
          organization = projectObj("organization").str,
          repository = projectObj("repository").str
        ),
        releaseDate = Instant.parse(obj("releaseDate").str)
      )
    }

  private def resolveScala3Minor(
      coordinate: ArtifactCoordinate,
      resolver: Resolver
  ): IO[Option[String]] =
    fetchText(
      uri = coordinate.pomUri,
      description = s"POM for ${coordinate.groupId}:${coordinate.artifactId}:${coordinate.version}",
      resolver = resolver
    ).map { pomText =>
      parseScala3MinorFromPom(pomText, coordinate)
    }

  private def parseScala3MinorFromPom(pomText: String, coordinate: ArtifactCoordinate): Option[String] =
    val document = parseXml(pomText)
    val properties = extractProperties(document)
    val dependencyVersions =
      extractDependencies(document)
        .collect {
          case (groupId, artifactId, version)
              if groupId == "org.scala-lang" &&
                (artifactId == "scala3-library_3" || artifactId == "scala3-compiler_3") =>
            resolveProperty(version, properties)
        }
        .flatten
        .flatMap(toScalaMinor)
        .distinct

    dependencyVersions match
      case minor :: _ =>
        Some(minor)
      case Nil if coordinate.groupId == "org.scala-lang" &&
          (coordinate.artifactId == "scala3-library_3" || coordinate.artifactId == "scala3-compiler_3") =>
        toScalaMinor(coordinate.version)
      case Nil =>
        None

  private def parseXml(text: String): Document =
    val factory = DocumentBuilderFactory.newInstance()
    factory.setNamespaceAware(false)
    val builder = factory.newDocumentBuilder()
    val input = InputSource(StringReader(text))
    builder.parse(input)

  private def extractProperties(document: Document): Map[String, String] =
    val root = document.getDocumentElement
    val base =
      Map(
        "project.version" -> childText(root, "version").getOrElse(""),
        "pom.version" -> childText(root, "version").getOrElse("")
      ).filter(_._2.nonEmpty)

    childElement(root, "properties") match
      case None => base
      case Some(propertiesElement) =>
        base ++ nodeListElements(propertiesElement.getChildNodes).collect {
          case element: Element =>
            element.getTagName -> element.getTextContent.trim
        }.filter { case (_, value) => value.nonEmpty }

  private def extractDependencies(document: Document): List[(String, String, String)] =
    nodeListElements(document.getElementsByTagName("dependency")).collect {
      case element: Element =>
        (
          childText(element, "groupId").getOrElse(""),
          childText(element, "artifactId").getOrElse(""),
          childText(element, "version").getOrElse("")
        )
    }.filter { case (groupId, artifactId, version) =>
      groupId.nonEmpty && artifactId.nonEmpty && version.nonEmpty
    }

  private def childElement(parent: Element, tag: String): Option[Element] =
    nodeListElements(parent.getChildNodes).collectFirst {
      case element: Element if element.getTagName == tag => element
    }

  private def childText(parent: Element, tag: String): Option[String] =
    childElement(parent, tag).map(_.getTextContent.trim).filter(_.nonEmpty)

  private def resolveProperty(value: String, properties: Map[String, String], depth: Int = 0): Option[String] =
    if depth > 10 then None
    else
      val resolved = PropertyPattern.replaceAllIn(
        value,
        m => properties.get(m.group(1)).getOrElse(m.matched)
      )
      if resolved == value then Some(resolved.trim).filter(_.nonEmpty)
      else if PropertyPattern.findFirstIn(resolved).nonEmpty then resolveProperty(resolved, properties, depth + 1)
      else Some(resolved.trim).filter(_.nonEmpty)

  private def resolveScala2Minor(detail: ArtifactDetail): Option[String] =
    Scala2MinorPattern.findFirstMatchIn(detail.binaryVersion).map(_.group(1))

  private def isScala3Artifact(detail: ArtifactDetail): Boolean =
    detail.language == "3" ||
    detail.binaryVersion.endsWith("_3") ||
    detail.binaryVersion.contains("_3_") ||
    detail.coordinate.artifactId.endsWith("_3")

  private def toScalaMinor(version: String): Option[String] =
    ScalaMajorMinorPattern.findFirstMatchIn(version).map(m => s"${m.group(1)}.${m.group(2)}")

  private def buildReport(
      contexts: List[ProjectContext],
      totalProjectsFromScaladex: Int,
      config: CliConfig
  ): Report =
    val buckets = buildBuckets(config.asOf)

    val projectsWithLatestArtifacts = contexts.count(_.latestArtifactCount > 0)
    val projectsWithReleaseDate = contexts.count(_.lastReleaseDate.nonEmpty)
    val projectsWithScalaMinor = contexts.count(_.scalaMinors.nonEmpty)
    val projectsWithErrors = contexts.count(_.errors.nonEmpty)
    val projectsWithoutLatestArtifacts = contexts.count(_.latestArtifactCount == 0)
    val projectsWithoutReleaseDate = contexts.count(_.lastReleaseDate.isEmpty)
    val projectsWithoutScalaMinor = contexts.count(_.scalaMinors.isEmpty)
    val projectsWithFutureReleaseDate = contexts.count(_.lastReleaseLocalDate.exists(_.isAfter(config.asOf)))

    val includedProjectEntries =
      contexts.flatMap { context =>
        context.lastReleaseLocalDate match
          case Some(releaseDate) if !releaseDate.isAfter(config.asOf) && context.scalaMinors.nonEmpty =>
            context.scalaMinors.map(minor => (context.project.id, releaseDate, minor))
          case _ =>
            Nil
      }

    val scalaMinors = includedProjectEntries.map(_._3).distinct.sorted(using minorOrdering)
    val overallCountsByMinor = includedProjectEntries.groupMapReduce(_._3)(_ => 1)(_ + _)

    val bucketStats = buckets.map { bucket =>
      val entries = includedProjectEntries.filter { case (_, releaseDate, _) => bucket.contains(releaseDate) }
      BucketStats(
        bucket = bucket,
        totalProjectMinorPairs = entries.size,
        countsByMinor = entries.groupMapReduce(_._3)(_ => 1)(_ + _)
      )
    }

    Report(
      config = config,
      summary = Summary(
        totalProjectsFromScaladex = totalProjectsFromScaladex,
        projectsProcessed = contexts.size,
        projectsWithLatestArtifacts = projectsWithLatestArtifacts,
        projectsWithReleaseDate = projectsWithReleaseDate,
        projectsWithScalaMinor = projectsWithScalaMinor,
        projectsIncludedInDistribution = includedProjectEntries.map(_._1).distinct.size,
        projectsWithoutLatestArtifacts = projectsWithoutLatestArtifacts,
        projectsWithoutReleaseDate = projectsWithoutReleaseDate,
        projectsWithoutScalaMinor = projectsWithoutScalaMinor,
        projectsWithFutureReleaseDate = projectsWithFutureReleaseDate,
        totalProjectMinorPairs = includedProjectEntries.size,
        artifactDetailsResolved = contexts.map(_.resolvedArtifactCount).sum,
        artifactDetailFailures = contexts.map(_.artifactDetailFailures).sum,
        scala3ArtifactsSeen = contexts.map(_.scala3ArtifactCount).sum,
        scala3PomsResolved = contexts.map(context => context.scala3ArtifactCount - context.scala3PomFailures).sum,
        scala3PomFailures = contexts.map(_.scala3PomFailures).sum,
        projectsWithErrors = projectsWithErrors
      ),
      scalaMinors = scalaMinors,
      overallCountsByMinor = overallCountsByMinor,
      buckets = bucketStats
    )

  private def buildBuckets(asOf: LocalDate): List[Bucket] =
    val threeMonthsAgo = asOf.minusMonths(3)
    val oneYearAgo = asOf.minusYears(1)
    val threeYearsAgo = asOf.minusYears(3)
    val fiveYearsAgo = asOf.minusYears(5)

    List(
      Bucket(
        id = "last-3-months",
        label = "Last 3 months",
        description = s"Released from $threeMonthsAgo through $asOf",
        startInclusive = Some(threeMonthsAgo),
        endInclusive = Some(asOf)
      ),
      Bucket(
        id = "last-year-excluding-3-months",
        label = "Last year, older than 3 months",
        description = s"Released from $oneYearAgo through ${threeMonthsAgo.minusDays(1)}",
        startInclusive = Some(oneYearAgo),
        endInclusive = Some(threeMonthsAgo.minusDays(1))
      ),
      Bucket(
        id = "last-3-years-excluding-1-year",
        label = "Last 3 years, older than 1 year",
        description = s"Released from $threeYearsAgo through ${oneYearAgo.minusDays(1)}",
        startInclusive = Some(threeYearsAgo),
        endInclusive = Some(oneYearAgo.minusDays(1))
      ),
      Bucket(
        id = "last-5-years-excluding-3-years",
        label = "Last 5 years, older than 3 years",
        description = s"Released from $fiveYearsAgo through ${threeYearsAgo.minusDays(1)}",
        startInclusive = Some(fiveYearsAgo),
        endInclusive = Some(threeYearsAgo.minusDays(1))
      ),
      Bucket(
        id = "older-than-5-years",
        label = "Older than 5 years",
        description = s"Released on or before ${fiveYearsAgo.minusDays(1)}",
        startInclusive = None,
        endInclusive = Some(fiveYearsAgo.minusDays(1))
      )
    )

  private def renderTableReport(report: Report): String =
    val overallHeaders = List("Scala minor", "Project/minor pairs", "Share")
    val overallRows = report.scalaMinors.map { minor =>
      val count = report.overallCountsByMinor.getOrElse(minor, 0)
      List(minor, count.toString, formatPercentage(count, report.summary.totalProjectMinorPairs))
    }

    val bucketHeaders = "Bucket" :: "Range" :: "Pairs" :: report.scalaMinors
    val bucketRows = report.buckets.map { bucketStats =>
      val counts = report.scalaMinors.map(minor => bucketStats.countsByMinor.getOrElse(minor, 0).toString)
      List(bucketStats.bucket.label, bucketStats.bucket.description, bucketStats.totalProjectMinorPairs.toString) ++ counts
    }

    val withinBucketHeaders = "Bucket" :: "Pairs" :: report.scalaMinors
    val withinBucketRows = report.buckets.map { bucketStats =>
      val shares = report.scalaMinors.map { minor =>
        formatPercentage(bucketStats.countsByMinor.getOrElse(minor, 0), bucketStats.totalProjectMinorPairs)
      }
      List(bucketStats.bucket.label, bucketStats.totalProjectMinorPairs.toString) ++ shares
    }

    List(
      s"As of ${report.config.asOf} (UTC)",
      s"Context output: ${report.config.contextOutputPath}",
      "",
      "Summary",
      s"  Total projects from Scaladex: ${report.summary.totalProjectsFromScaladex}",
      s"  Projects processed: ${report.summary.projectsProcessed}",
      s"  Projects with latest artifacts: ${report.summary.projectsWithLatestArtifacts}",
      s"  Projects with release date: ${report.summary.projectsWithReleaseDate}",
      s"  Projects with at least one resolved Scala minor: ${report.summary.projectsWithScalaMinor}",
      s"  Projects included in distribution: ${report.summary.projectsIncludedInDistribution}",
      s"  Projects without latest artifacts: ${report.summary.projectsWithoutLatestArtifacts}",
      s"  Projects without release date: ${report.summary.projectsWithoutReleaseDate}",
      s"  Projects without resolved Scala minor: ${report.summary.projectsWithoutScalaMinor}",
      s"  Projects with release date after as-of: ${report.summary.projectsWithFutureReleaseDate}",
      s"  Total project/minor pairs: ${report.summary.totalProjectMinorPairs}",
      s"  Artifact details resolved: ${report.summary.artifactDetailsResolved}",
      s"  Artifact detail failures: ${report.summary.artifactDetailFailures}",
      s"  Scala 3 artifacts inspected: ${report.summary.scala3ArtifactsSeen}",
      s"  Scala 3 POMs resolved: ${report.summary.scala3PomsResolved}",
      s"  Scala 3 POM failures: ${report.summary.scala3PomFailures}",
      s"  Projects with any resolution errors: ${report.summary.projectsWithErrors}",
      "",
      "Overall Scala minor distribution",
      renderTable(overallHeaders, overallRows),
      "",
      "Counts by release-age bucket and Scala minor",
      renderTable(bucketHeaders, bucketRows),
      "",
      "Within-bucket distribution (%)",
      renderTable(withinBucketHeaders, withinBucketRows)
    ).mkString("\n")

  private def renderJsonReport(report: Report): String =
    def bucketJson(bucketStats: BucketStats): ujson.Obj =
      ujson.Obj(
        "id" -> bucketStats.bucket.id,
        "label" -> bucketStats.bucket.label,
        "description" -> bucketStats.bucket.description,
        "startInclusive" -> bucketStats.bucket.startInclusive.map(date => ujson.Str(date.toString)).getOrElse(ujson.Null),
        "endInclusive" -> bucketStats.bucket.endInclusive.map(date => ujson.Str(date.toString)).getOrElse(ujson.Null),
        "totalProjectMinorPairs" -> bucketStats.totalProjectMinorPairs,
        "countsByScalaMinor" -> ujson.Obj.from(report.scalaMinors.map { minor =>
          minor -> ujson.Num(bucketStats.countsByMinor.getOrElse(minor, 0))
        }),
        "shareByScalaMinor" -> ujson.Obj.from(report.scalaMinors.map { minor =>
          minor -> ujson.Num(share(bucketStats.countsByMinor.getOrElse(minor, 0), bucketStats.totalProjectMinorPairs))
        })
      )

    val json =
      ujson.Obj(
        "asOf" -> report.config.asOf.toString,
        "contextOutputPath" -> report.config.contextOutputPath.toString,
        "summary" -> ujson.Obj(
          "totalProjectsFromScaladex" -> report.summary.totalProjectsFromScaladex,
          "projectsProcessed" -> report.summary.projectsProcessed,
          "projectsWithLatestArtifacts" -> report.summary.projectsWithLatestArtifacts,
          "projectsWithReleaseDate" -> report.summary.projectsWithReleaseDate,
          "projectsWithScalaMinor" -> report.summary.projectsWithScalaMinor,
          "projectsIncludedInDistribution" -> report.summary.projectsIncludedInDistribution,
          "projectsWithoutLatestArtifacts" -> report.summary.projectsWithoutLatestArtifacts,
          "projectsWithoutReleaseDate" -> report.summary.projectsWithoutReleaseDate,
          "projectsWithoutScalaMinor" -> report.summary.projectsWithoutScalaMinor,
          "projectsWithFutureReleaseDate" -> report.summary.projectsWithFutureReleaseDate,
          "totalProjectMinorPairs" -> report.summary.totalProjectMinorPairs,
          "artifactDetailsResolved" -> report.summary.artifactDetailsResolved,
          "artifactDetailFailures" -> report.summary.artifactDetailFailures,
          "scala3ArtifactsSeen" -> report.summary.scala3ArtifactsSeen,
          "scala3PomsResolved" -> report.summary.scala3PomsResolved,
          "scala3PomFailures" -> report.summary.scala3PomFailures,
          "projectsWithErrors" -> report.summary.projectsWithErrors
        ),
        "scalaMinors" -> ujson.Arr.from(report.scalaMinors.map(ujson.Str(_))),
        "overallCountsByScalaMinor" -> ujson.Obj.from(report.scalaMinors.map { minor =>
          minor -> ujson.Num(report.overallCountsByMinor.getOrElse(minor, 0))
        }),
        "overallShareByScalaMinor" -> ujson.Obj.from(report.scalaMinors.map { minor =>
          minor -> ujson.Num(share(report.overallCountsByMinor.getOrElse(minor, 0), report.summary.totalProjectMinorPairs))
        }),
        "buckets" -> ujson.Arr.from(report.buckets.map(bucketJson))
      )

    ujson.write(json, indent = 2)

  private def renderTable(headers: List[String], rows: List[List[String]]): String =
    val allRows = headers :: rows
    val widths =
      headers.indices.map { idx =>
        allRows.iterator.map(_.lift(idx).getOrElse("")).map(_.length).max
      }.toList

    def renderRow(row: List[String]): String =
      row.zipWithIndex
        .map { case (value, idx) => value.padTo(widths(idx), ' ') }
        .mkString("  ")

    val separator = widths.map("-" * _).mkString("  ")
    (renderRow(headers) :: separator :: rows.map(renderRow)).mkString("\n")

  private def share(count: Int, total: Int): Double =
    if total == 0 then 0.0 else count.toDouble / total.toDouble

  private def formatPercentage(count: Int, total: Int): String =
    if total == 0 then "0.0%"
    else f"${share(count, total) * 100.0}%.1f%%"

  private def minorOrdering: Ordering[String] = Ordering.by { version =>
    version.split('.').toList match
      case major :: minor :: Nil if major.forall(_.isDigit) && minor.forall(_.isDigit) =>
        (0, major.toInt, minor.toInt, "")
      case _ =>
        (1, Int.MaxValue, Int.MaxValue, version)
  }

  private def writeContextOutput(contexts: List[ProjectContext], path: Path): IO[Unit] =
    IO.blocking {
      Option(path.getParent).foreach(parent => Files.createDirectories(parent))
      val json = ujson.Arr.from(contexts.map(_.asJson))
      Files.writeString(path, ujson.write(json, indent = 2) + "\n")
    }

  private def getJson(uri: URI, description: String, resolver: Resolver): IO[ujson.Value] =
    fetchText(uri, description, resolver).map(text => ujson.read(text))

  private def fetchText(uri: URI, description: String, resolver: Resolver): IO[String] =
    retrying(resolver.retries) {
      IO.blocking {
        val request =
          HttpRequest
            .newBuilder(uri)
            .timeout(JDuration.ofSeconds(30))
            .header("Accept", "application/json, text/xml, application/xml, text/plain;q=0.9, */*;q=0.8")
            .header("User-Agent", UserAgent)
            .GET()
            .build()

        val response =
          resolver.httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))

        response.statusCode() match
          case 200 =>
            response.body()
          case status =>
            throw RequestFailedException(s"$description failed with HTTP $status for $uri")
      }
    }

  private def retrying[A](retries: Int)(io: IO[A]): IO[A] =
    io.handleErrorWith { err =>
      if retries <= 0 then IO.raiseError(err)
      else IO.sleep((retries + 1).seconds) *> retrying(retries - 1)(io)
    }

  private def collectErrors(
      detailResults: List[Either[Throwable, ArtifactDetail]],
      scala3MinorResults: List[Either[Throwable, Option[String]]]
  ): List[String] =
    val detailErrors = detailResults.collect { case Left(err) => s"artifact detail: ${err.getMessage}" }
    val pomErrors = scala3MinorResults.collect {
      case Left(err) => s"scala3 pom: ${err.getMessage}"
      case Right(None) =>
        "scala3 pom: no scala3-library_3 or scala3-compiler_3 dependency version found"
    }
    (detailErrors ++ pomErrors).distinct.take(8)

  private def encodePathSegment(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

  private def nodeListElements(nodeList: org.w3c.dom.NodeList): List[Element] =
    (0 until nodeList.getLength).toList.flatMap { idx =>
      Option(nodeList.item(idx)).collect { case element: Element => element }
    }

@main def scaladexEcosystemScalaMinorDistribution(args: String*): Unit =
  ScaladexEcosystemScalaMinorDistributionApp.run(args.toList).unsafeRunSync()
