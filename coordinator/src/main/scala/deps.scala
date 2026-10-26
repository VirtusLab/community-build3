import scala.concurrent.*
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.TimeUnit.SECONDS
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.jsoup.HttpStatusException
import Scaladex.ProjectArtifact

def loadProjects(scalaBinaryVersion: String)(using scaladex: Scaladex): Seq[StarredProject] =
  scaladex.starredProjects(scalaBinaryVersion)

case class ModuleInVersion(version: String, modules: Seq[String])
enum CandidateProject:
  def project: Project
  case BuildAll(project: Project)
  case BuildSelected(project: Project, mvs: Seq[ModuleInVersion])
case class ProjectModules(project: Project, mvs: Seq[ModuleInVersion])

/** JVM Scala 3 library artifacts we can resolve and test (not sbt plugins or cross-platform variants). */
private def isTestableArtifact(artifactId: String): Boolean =
  artifactId match
    case s"${_}_native${_}"          => false
    case s"${_}_sjs${_}"             => false
    case s"${_}_sbt1" | s"${_}_sbt2" => false
    case s"${_}_sbt${_}"             => false
    case s"${_}_3"                   => true
    case _                           => false

private def isTestableModuleName(module: String): Boolean =
  module match
    case s"${_}_sbt1" | s"${_}_sbt2" => false
    case _                           => isTestableArtifact(s"${module}_3")

/** Higher semver first. A release outranks a prerelease; `M4` outranks `M1`. */
private def versionSortKey(version: String): (Int, Int, Int, Int, Int) =
  val sem = SemVersion.unapply(version).getOrElse(SemVersion(0, 0, 0))
  val (preKind, preNum) = sem.milestone match
    case None => (5, 0)
    case Some(milestone) =>
      val lower = milestone.toLowerCase
      val kind =
        if lower.startsWith("rc") || lower.startsWith("cr") then 4
        else if lower.startsWith("milestone") || lower.startsWith("m") then 3
        else if lower.startsWith("beta") then 2
        else if lower.startsWith("alpha") || lower.startsWith("preview") then 1
        else 0
      val preNum = """\d+""".r.findAllIn(milestone).toList.lastOption.flatMap(_.toIntOption).getOrElse(0)
      (kind, preNum)
  (sem.major, sem.minor, sem.patch, preKind, preNum)

private def modulesForVersions(
    artifactsByVersion: Map[String, Seq[ProjectArtifact]],
    versions: Seq[String]
): Seq[ModuleInVersion] =
  versions
    .map { version =>
      ModuleInVersion(
        version = version,
        modules = artifactsByVersion(version)
          .map(_.artifactId.stripSuffix("_3"))
          .filter(isTestableModuleName)
          .distinct
      )
    }
    .filter(_.modules.nonEmpty)

def loadScaladexProject(releaseCutOffDate: Option[LocalDate] = None)(
    project: Project
)(using scaladex: Scaladex): AsyncResponse[ProjectModules] =
  scaladex.artifacts(project).flatMap(projectModulesFromArtifacts(releaseCutOffDate, project, _))

private def projectModulesFromArtifacts(
    releaseCutOffDate: Option[LocalDate],
    project: Project,
    allArtifacts: Seq[ProjectArtifact]
)(using scaladex: Scaladex): AsyncResponse[ProjectModules] =
  val scala3JvmArtifacts = allArtifacts.filter(a => isTestableArtifact(a.artifactId))
    if scala3JvmArtifacts.isEmpty then
      val detail =
        if allArtifacts.isEmpty then "no artifacts on Scaladex"
        else s"0 testable JVM _3 artifacts among ${allArtifacts.size} on Scaladex"
      CoordinatorLog.exclude(project, "no testable JVM _3 artifacts", detail)
    val artifactsByVersion = scala3JvmArtifacts.groupBy(_.version)
    val orderedVersions =
      releaseCutOffDate match
        case None =>
          // One artifacts-list call per project. Per-version detail calls are what turned a
          // Cloudflare 429 into a 6h retry loop (~2k projects × every published version).
          Future.successful(artifactsByVersion.keys.toSeq.sortBy(versionSortKey).reverse)
        case Some(cutOff) =>
          artifactsByVersion.keys.toList
            .foldLeft(Future.successful(List.empty[(String, java.time.OffsetDateTime)])) { (accF, version) =>
              accF.flatMap { acc =>
                scaladex.artifact(artifactsByVersion(version).head).map { artifact =>
                  if artifact.platform == "jvm" && cutOff.isAfter(artifact.releaseLocalData) then
                    (version, artifact.releaseDate) :: acc
                  else acc
                }
              }
            }
            .map(_.sortBy(-_._2.toEpochSecond).map(_._1))
    orderedVersions.map { versions =>
      val nonEmptyVersions = modulesForVersions(artifactsByVersion, versions)
      if nonEmptyVersions.isEmpty && artifactsByVersion.nonEmpty then
        CoordinatorLog.exclude(
          project,
          "no testable JVM _3 artifacts",
          s"no versions passed filters (releaseCutOffDate=${releaseCutOffDate.isDefined})"
        )
      ProjectModules(project, nonEmptyVersions)
    }

/** JVM Scala 3 versions published on Scaladex (one cheap [[Scaladex.artifacts]] call). */
private def testableArtifactVersions(artifacts: Seq[ProjectArtifact]): Set[String] =
  artifacts.filter(a => isTestableArtifact(a.artifactId)).map(_.version).toSet

private def readCachedProjectModules(project: Project)(using
    driver: CacheDriver[Project, ProjectModules]
): Option[ProjectModules] =
  val dest = driver.dest(project)
  if Files.exists(dest) then Some(driver.load(Files.readString(dest), project))
  else None

private def writeCachedProjectModules(pm: ProjectModules)(using
    driver: CacheDriver[Project, ProjectModules]
): Unit =
  val dest = driver.dest(pm.project)
  Files.createDirectories(dest.getParent)
  Files.writeString(dest, driver.write(pm))

private def refreshProjectModules(
    releaseCutOffDate: Option[LocalDate],
    project: Project,
    artifacts: Seq[ProjectArtifact],
    reason: String
)(using
    scaladex: Scaladex,
    driver: CacheDriver[Project, ProjectModules]
): AsyncResponse[ProjectModules] =
  println(s"Refreshing Scaladex project modules for ${project.coordinates} ($reason)")
  projectModulesFromArtifacts(releaseCutOffDate, project, artifacts).map { pm =>
    writeCachedProjectModules(pm)
    pm
  }

/** Stale disk cache when Scaladex gives up on this project. An outage ([[Scaladex.Unavailable]]) is not recovered. */
private def modulesAfterScaladexGaveUp(project: Project, err: Scaladex.GaveUp)(using
    driver: CacheDriver[Project, ProjectModules]
): ProjectModules =
  CoordinatorProgress.scaladexSkipped()
  readCachedProjectModules(project) match
    case Some(cached) =>
      CoordinatorLog.warn(project, "using stale Scaladex cache", err.getMessage)
      cached
    case None =>
      CoordinatorLog.exclude(project, "Scaladex lookup failed", err.getMessage)
      ProjectModules(project, Nil)

/** Cached project modules with a cheap Scaladex version check before skipping the full load. */
def loadProjectModulesWithVersionCheck(releaseCutOffDate: Option[LocalDate] = None)(
    project: Project
)(using scaladex: Scaladex, driver: CacheDriver[Project, ProjectModules]): AsyncResponse[ProjectModules] =
  scaladex
    .artifacts(project)
    .flatMap { artifacts =>
      val freshVersions = testableArtifactVersions(artifacts)
      readCachedProjectModules(project) match
        case Some(cached) if cached.mvs.map(_.version).toSet == freshVersions =>
          Future.successful(cached)
        case Some(cached) =>
          val cachedVersions = cached.mvs.map(_.version).toSet
          val added = freshVersions -- cachedVersions
          val removed = cachedVersions -- freshVersions
          refreshProjectModules(
            releaseCutOffDate,
            project,
            artifacts,
            s"versions changed: +${added.mkString(", ")} -${removed.mkString(", ")}"
          )
        case None =>
          refreshProjectModules(releaseCutOffDate, project, artifacts, "no cache")
    }
    .recover { case err: Scaladex.GaveUp =>
      modulesAfterScaladexGaveUp(project, err)
    }

/** Fail the run when too many projects were skipped, so a partial plan is not published. */
def ensureScaladexCoverage(graph: DependencyGraph): Unit =
  val skipped = CoordinatorProgress.scaladexSkippedCount
  val seen = graph.projects.size + skipped
  if seen >= 20 && skipped * 20 > seen then
    sys.error(
      s"Scaladex lookups failed for $skipped of $seen projects; refusing to write a partial build plan"
    )

/** Reuse on-disk Scaladex module cache (or buildConfig.json targets); never call Scaladex. */
def loadProjectModulesOffline(
    project: Project,
    buildConfigSeed: BuildConfigSeedIndex
)(using driver: CacheDriver[Project, ProjectModules]): AsyncResponse[ProjectModules] =
  Future {
    readCachedProjectModules(project) match
      case Some(cached) =>
        println(
          s"Using cached Scaladex project modules for ${project.coordinates} (--offline-scaladex)"
        )
        cached
      case None =>
        buildConfigSeed.projectModules(project) match
          case Some(seeded) =>
            println(
              s"Seeding project modules for ${project.coordinates} from buildConfig.json (--offline-scaladex)"
            )
            writeCachedProjectModules(seeded)
            seeded
          case None =>
            throw RuntimeException(
              s"No Scaladex modules cache for ${project.coordinates} with --offline-scaladex. " +
                s"Expected data/projectModules/${project.organization}_${project.repository}.csv " +
                "or a targets entry in .github/workflows/buildConfig.json"
            )
  }

case class VersionedModules(modules: ModuleInVersion, semVersion: SemVersion)
case class ModuleVersion(name: String, version: String, p: Project)

private val MaxMavenInfoAttempts = 5

def loadMavenInfo(scalaBinaryVersion: String, buildConfigSeed: BuildConfigSeedIndex)(
    projectModules: CandidateProject.BuildSelected
): AsyncResponse[LoadedProject] =
  import projectModules.project.{repository, organization}
  val repoName = s"https://github.com/$organization/$repository.git"
  require(
    projectModules.mvs.nonEmpty,
    s"Empty modules list in ${projectModules.project}"
  )
  val byRelease = projectModules.mvs.sortBy(mv => versionSortKey(mv.version)).reverse
  val ModuleInVersion(version, modules) = byRelease
    .find(v => findTag(repoName, v.version).isDefined)
    .getOrElse(byRelease.head)

  val tasks = modules.map { module =>
    def tryFetch(backoffSeconds: Int, attempt: Int): AsyncResponse[Option[Target]] = {
      inline def backoff(ex: Throwable, retryable: Boolean) = {
        val canRetry = retryable && attempt < MaxMavenInfoAttempts
        val detail = CoordinatorRuntime.describeFailure(ex)
        val action =
          if canRetry then s"retry with backoff ${backoffSeconds}s (attempt $attempt/$MaxMavenInfoAttempts)"
          else if retryable then s"giving up after $attempt attempts"
          else "giving up"
        Console.err.println(
          s"Failed to load maven info for $organization/$repository module=$module version=$version ($detail): $action"
        )
        if canRetry then
          SECONDS.sleep(backoffSeconds)
          tryFetch((backoffSeconds * 2).min(60), attempt + 1)
        else Future.successful(None)
      }
      Future({
        val target = cached {
          Maven.asTarget(scalaBinaryVersion, buildConfigSeed)(_)
        }(ModuleVersion(module, version, projectModules.project))
        Some(target)
      })
        .recoverWith {
          case ex: UnknownHostException                           => backoff(ex, retryable = true)
          case ex: SocketTimeoutException                         => backoff(ex, retryable = true)
          case ex: HttpStatusException if ex.getStatusCode == 503 =>
            backoff(ex, retryable = true)
          case ex: HttpStatusException if ex.getStatusCode >= 500 =>
            backoff(ex, retryable = true)
          case ex: java.net.ConnectException if ex.getMessage().contains("Operation timed out") =>
            backoff(ex, retryable = true)
          case ex: java.net.http.HttpTimeoutException =>
            backoff(ex, retryable = true)
          case ex: Exception =>
            backoff(ex, retryable = false)
        }
    }
    tryFetch(1, attempt = 1)
  }

  Future
    .sequence(tasks)
    .map: results =>
      val targets = results.flatten
      val failedModules =
        modules.zip(results).collect { case (module, None) => module }
      if targets.nonEmpty && failedModules.nonEmpty then
        CoordinatorLog.warn(
          projectModules.project,
          "partial Maven load",
          s"@ $version loaded ${targets.size}/${modules.size} modules; failed: ${failedModules.mkString(", ")}"
        )
      else if targets.isEmpty then
        CoordinatorLog.exclude(
          projectModules.project,
          "Maven metadata load failed",
          s"@ $version for modules: ${modules.mkString(", ")}"
        )
      LoadedProject(projectModules.project, version, targets)

  /** @param scalaBinaryVersion
    *   Scala binary version name (major.minor) or `3` for scala 3 - following scaladex's convention
    */
def loadDepenenecyGraph(
    scalaBinaryVersion: String,
    minStarsCount: Int,
    maxProjectsCount: Option[Int] = None,
    requiredProjects: Seq[Project] = Nil,
    customProjects: Seq[Project] = Nil,
    filterPatterns: Seq[String] = Nil,
    releaseCutOffDate: Option[LocalDate] = None,
    offlineScaladex: Boolean = false,
    buildConfigSeedPath: os.Path = workflowsDir / "buildConfig.json"
): AsyncResponse[DependencyGraph] =
  given Scaladex = Scaladex()
  val patterns = filterPatterns.map(_.r)
  val buildConfigSeed = BuildConfigSeedIndex(buildConfigSeedPath)
  if offlineScaladex then println("Scaladex offline mode: reusing data/projectModules (or buildConfig.json targets)")
  def loadProject(p: Project): () => AsyncResponse[CandidateProject] =
    () =>
      CoordinatorProgress.setDetail(s"scaladex ${p.coordinates}")
      if customProjects.contains(p) then Future.successful(CandidateProject.BuildAll(p))
      else
        val modules =
          if offlineScaladex then loadProjectModulesOffline(p, buildConfigSeed)
          else loadProjectModulesWithVersionCheck(releaseCutOffDate)(p)
        modules.map { pm =>
          val filtered = projectModulesFilter(patterns)(pm)
          if filtered.mvs.isEmpty then
            if pm.mvs.isEmpty then ()
            else
              CoordinatorLog.exclude(
                filtered.project,
                "empty module list after filters",
                s"${pm.mvs.size} version(s) before pattern/module filtering"
              )
          CandidateProject.BuildSelected(filtered.project, filtered.mvs)
        }

  val required = LazyList
    .from(requiredProjects)
    .map(loadProject)

  val customProjectsStream = customProjects.to(LazyList).map(loadProject)

  val optionalStream =
    customProjectsStream #:::
      cachedSingle("projects.csv")(loadProjects(scalaBinaryVersion))
        .takeWhile(_.stars >= minStarsCount)
        .to(LazyList)
        .map(loadProject)
  def optional(from: Int, limit: Option[Int]) =
    limit.foldLeft(optionalStream.drop(from))(_.take(_))

  val projectLoadBatchSize = 4
  def load(
      candidates: LazyList[() => AsyncResponse[CandidateProject]]
  ): Future[Seq[Option[LoadedProject]]] =
    def runBatch(
        rest: LazyList[() => AsyncResponse[CandidateProject]],
        acc: Vector[Option[LoadedProject]],
        idx: Int
    ): Future[Seq[Option[LoadedProject]]] =
      if rest.isEmpty then Future.successful(acc.toSeq)
      else
        val (batch, tail) = rest.splitAt(projectLoadBatchSize)
        val started = batch.toList.zipWithIndex.map { (start, offset) =>
          val projectIndex = idx + offset
          start().flatMap { project =>
            val name = s"${project.project.organization}/${project.project.repository}"
            project match
              case CandidateProject.BuildAll(buildAll) =>
                Future.successful(Some(LoadedProject(buildAll, "HEAD", Seq(Target.BuildAll))))
              case CandidateProject.BuildSelected(_, mvs) if mvs.isEmpty =>
                Future.successful(None)
              case candidate: CandidateProject.BuildSelected =>
                CoordinatorProgress.setDetail(s"maven $name")
                loadMavenInfo(scalaBinaryVersion, buildConfigSeed)(candidate)
                  .map { result =>
                    CoordinatorProgress.mavenInfoLoaded()
                    println(s"Loaded Maven info #${projectIndex + 1} for $name")
                    Some(result)
                  }
                  .recover {
                    case ex: org.jsoup.HttpStatusException if ex.getStatusCode() == 404 =>
                      System.err.println(s"Missing Maven info: ${ex.getUrl()}")
                      None
                  }
          }
        }
        Future.sequence(started).flatMap { rows =>
          runBatch(tail, acc ++ rows, idx + rows.size)
        }
    runBatch(candidates, Vector.empty, 0)

  load(
    required #::: optional(
      from = 0,
      limit = maxProjectsCount
        .map(_ - requiredProjects.length - customProjects.length)
        .map(_ max 0)
    )
  ).flatMap { loaded =>
    val available = loaded.flatten
    def skip = Future.successful(available)
    maxProjectsCount.fold(skip) { limit =>
      val remainingSlots = limit - available.size
      if remainingSlots <= 0 then skip
      else {
        val continueFrom = loaded.size - required.size
        // Load '10 < 1/2n < 50' more projects then number of remaining slots to filter out possibly empty entries
        val toLoad =
          remainingSlots + (remainingSlots * 0.5).toInt.max(10).min(50)
        println(
          s"Filling remaining ${remainingSlots} slots, trying to load $toLoad next projects"
        )
        load(optional(from = continueFrom, limit = Some(toLoad)))
          .map(available ++ _.flatten.take(remainingSlots))
      }
    }
  }.map(DependencyGraph(scalaBinaryVersion, _))

def projectModulesFilter(
    filterPatterns: Seq[util.matching.Regex]
)(project: ProjectModules): ProjectModules = {
  val p = project.project
  def matchPatternAndLog(v: String): Boolean = {
    filterPatterns
      .find(_.matches(v))
      .tapEach { pattern =>
        println(s"Excluding entry $v, matched by pattern ${pattern.regex}")
      }
      .nonEmpty
  }

  project.copy(mvs =
    project.mvs
      .collect {
        case mvs @ ModuleInVersion(version, modules)
            // Each entry is represented in form of `<organization>:<project/module>:<version>`
            // Filter out whole project for given version
            if !matchPatternAndLog(s"${p.organization}:${p.repository}:$version") =>
          mvs.copy(modules = modules.filter(isTestableModuleName).filter { module =>
            // Filter out modules for given version
            !matchPatternAndLog(s"${p.organization}:$module:$version")
          })
      }
      .filter(_.modules.nonEmpty)
  )
}
