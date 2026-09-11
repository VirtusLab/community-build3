import org.jsoup._
import scala.jdk.CollectionConverters.*
import scala.concurrent.*
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.TimeUnit.SECONDS
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import Scaladex.{ProjectArtifact, ScaladexUrl}

import scala.language.implicitConversions

// TODO scala3 should be more robust
def loadProjects(scalaBinaryVersion: String): Seq[StarredProject] =
  val commonSearchParams = Map(
    "language" -> scalaBinaryVersion,
    "platform" -> "jvm",
    "sort" -> "stars",
    "q" -> "*"
  ).map(_ + "=" + _).mkString("&")
  def load(page: Int): Seq[StarredProject] = try {
    val d = Jsoup
      .connect(
        s"$ScaladexUrl/search?${commonSearchParams}&page=$page"
      )
      .get()
    d.select(".list-result .row").asScala.flatMap { e =>
      e.select("h4").get(0).text().takeWhile(!_.isWhitespace) match {
        case s"${organization}/${repository}" =>
          for ghStars <- e
              .select(".stats [title=Stars]")
              .asScala
              .headOption
              .flatMap(_.text.toIntOption)
              .orElse(Some(-1))
          yield StarredProject(organization, repository)(ghStars)
        case _ => None
      }
    }.toSeq
  } catch{case err: SocketTimeoutException => 
    println(s"retry load projects, page=$page, err=$err")
    load(page)
  }
  LazyList
    .from(1) // page 0 and page 1 have the same content
    .map(load)
    .takeWhile(_.nonEmpty)
    .flatten
    .sortBy(-_.stars)

case class ModuleInVersion(version: String, modules: Seq[String])
enum CandidateProject:
  def project: Project
  case BuildAll(project: Project)
  case BuildSelected(project: Project, mvs: Seq[ModuleInVersion])
case class ProjectModules(project: Project, mvs: Seq[ModuleInVersion])

/** JVM Scala 3 library artifacts we can resolve and test (not sbt plugins or cross-platform variants). */
private def isTestableArtifact(artifactId: String): Boolean =
  artifactId match
    case s"${_}_native${_}"           => false
    case s"${_}_sjs${_}"              => false
    case s"${_}_sbt1" | s"${_}_sbt2" => false
    case s"${_}_sbt${_}"              => false
    case s"${_}_3"                    => true
    case _                            => false

private def isTestableModuleName(module: String): Boolean =
  module match
    case s"${_}_sbt1" | s"${_}_sbt2" => false
    case _                            => isTestableArtifact(s"${module}_3")

def loadScaladexProject(releaseCutOffDate: Option[LocalDate] = None)(
    project: Project
)(using scaladex: Scaladex): AsyncResponse[ProjectModules] =
  scaladex.artifacts(project).map: allArtifacts =>
    buildProjectModules(project, allArtifacts, releaseCutOffDate)

/** Build [[ProjectModules]] from Scaladex GAVs + Maven Central versions + git tags. */
private def buildProjectModules(
    project: Project,
    allArtifacts: Seq[ProjectArtifact],
    releaseCutOffDate: Option[LocalDate]
): ProjectModules =
  val scala3JvmArtifacts = allArtifacts.filter(a => isTestableArtifact(a.artifactId))
  if scala3JvmArtifacts.isEmpty then
    val detail =
      if allArtifacts.isEmpty then "no artifacts on Scaladex"
      else s"0 testable JVM _3 artifacts among ${allArtifacts.size} on Scaladex"
    CoordinatorLog.exclude(project, "no testable JVM _3 artifacts", detail)
    ProjectModules(project, Nil)
  else
    buildProjectModulesFromArtifacts(project, scala3JvmArtifacts, releaseCutOffDate)

/** Build [[ProjectModules]] once Scaladex has yielded at least one testable JVM artifact. */
private def buildProjectModulesFromArtifacts(
    project: Project,
    scala3JvmArtifacts: Seq[ProjectArtifact],
    releaseCutOffDate: Option[LocalDate]
): ProjectModules =
  val knownModules =
    scala3JvmArtifacts
      .map(_.artifactId.stripSuffix("_3"))
      .filter(isTestableModuleName)
      .distinct
  val gavs =
    scala3JvmArtifacts
      .map(a => (a.groupId, a.artifactId))
      .distinct

  // version -> modules known to publish that version on Central
  val mavenModulesByVersion =
    scala.collection.mutable.Map.empty[String, scala.collection.mutable.Set[String]]
  val mavenDates = scala.collection.mutable.Map.empty[String, LocalDate]
  var anyMavenSuccess = false

  for (groupId, artifactId) <- gavs do
    val module = artifactId.stripSuffix("_3")
    if isTestableModuleName(module) then
      Maven.listVersionsWithDates(groupId, artifactId, fetchDates = releaseCutOffDate.isDefined) match
        case Maven.VersionsLookup.Resolved(versions) =>
          if versions.nonEmpty then anyMavenSuccess = true
          for mv <- versions do
            mavenModulesByVersion
              .getOrElseUpdate(mv.version, scala.collection.mutable.Set.empty)
              .add(module)
            mv.releaseDate.foreach: date =>
              mavenDates.updateWith(mv.version):
                case Some(existing) => Some(if date.isAfter(existing) then date else existing)
                case None           => Some(date)
        case Maven.VersionsLookup.NotFound =>
          () // artifact not on Central — expected for private/custom publishes
        case Maven.VersionsLookup.Failure(ex) =>
          Console.err.println(
            s"Failed to list Maven versions for $groupId:$artifactId " +
              s"(${CoordinatorRuntime.describeFailure(ex)})"
          )

  val scaladexModulesByVersion: Map[String, Seq[String]] =
    scala3JvmArtifacts
      .groupBy(_.version)
      .view
      .mapValues: arts =>
        arts.map(_.artifactId.stripSuffix("_3")).filter(isTestableModuleName).distinct
      .toMap

  // Prefer Central version lists; fall back to Scaladex when nothing is on Central.
  // Drop sparse Maven-only versions (e.g. one outlier module on a different version line)
  // unless they also exist as a git tag — otherwise unioning all GAVs picks unrelated lines
  // (izumi csharp 1.5.x / 1.3.0 vs distage 1.3.0-M3).
  val gitTagVersions = listVersionLikeTags(
    s"https://github.com/${project.organization}/${project.repository}.git"
  )
  val taggedVersionSet = gitTagVersions.toSet

  val publishedModulesByVersion: Map[String, Seq[String]] =
    if anyMavenSuccess then
      val retained =
        retainedMavenVersions(
          knownModules,
          mavenModulesByVersion.view.mapValues(_.toSet).toMap,
          taggedVersionSet
        )
      mavenModulesByVersion.iterator
        .collect {
          case (version, mods) if retained.contains(version) =>
            version -> mods.toSeq
        }
        .toMap
    else
      Console.err.println(
        s"No Maven Central versions for ${project.coordinates}; falling back to Scaladex versions"
      )
      scaladexModulesByVersion

  val allVersions =
    (publishedModulesByVersion.keySet ++ taggedVersionSet).toSeq

  def passesCutoff(version: String): Boolean =
    releaseCutOffDate match
      case None => true
      case Some(cutoff) =>
        mavenDates.get(version) match
          case Some(date) => cutoff.isAfter(date)
          case None       => true // tag-only / undated: keep (riddl-style private publishes)

  // Newest-first by SemVer across Maven∪tags. Do not prefer tags over newer Maven-only
  // publishes (acsgh 1.3.0 has no tag but must beat tagged 1.2.16).
  val orderedVersions =
    allVersions.filter(passesCutoff).sorted(using versionOrdering.reverse)

  val versionModules =
    for version <- orderedVersions
    yield
      val modules =
        publishedModulesByVersion
          .getOrElse(version, knownModules)
          .filter(isTestableModuleName)
          .distinct
      ModuleInVersion(version, modules)

  val nonEmptyVersions = versionModules.filter(_.modules.nonEmpty)
  if nonEmptyVersions.isEmpty && allVersions.nonEmpty then
    CoordinatorLog.exclude(
      project,
      "no testable JVM _3 artifacts",
      s"no versions passed filters (releaseCutOffDate=${releaseCutOffDate.isDefined})"
    )
  ProjectModules(project, nonEmptyVersions)

/** Maven ∪ git-tag versions for cache freshness (Scaladex only supplies GAVs). */
private def freshProjectVersions(
    project: Project,
    artifacts: Seq[ProjectArtifact]
): Set[String] =
  val scala3Jvm = artifacts.filter(a => isTestableArtifact(a.artifactId))
  val knownModules =
    scala3Jvm
      .map(_.artifactId.stripSuffix("_3"))
      .filter(isTestableModuleName)
      .distinct
  val gavs = scala3Jvm.map(a => (a.groupId, a.artifactId)).distinct
  val mavenModulesByVersion =
    scala.collection.mutable.Map.empty[String, scala.collection.mutable.Set[String]]
  var anyMavenSuccess = false
  for (g, a) <- gavs do
    val module = a.stripSuffix("_3")
    if isTestableModuleName(module) then
      Maven.listVersions(g, a) match
        case Maven.VersionsLookup.Resolved(versions) =>
          if versions.nonEmpty then anyMavenSuccess = true
          for mv <- versions do
            mavenModulesByVersion
              .getOrElseUpdate(mv.version, scala.collection.mutable.Set.empty)
              .add(module)
        case Maven.VersionsLookup.NotFound => ()
        case Maven.VersionsLookup.Failure(ex) =>
          Console.err.println(
            s"Failed to list Maven versions for $g:$a while checking cache " +
              s"(${CoordinatorRuntime.describeFailure(ex)})"
          )
  val repoUrl = s"https://github.com/${project.organization}/${project.repository}.git"
  val tagVersions = listVersionLikeTags(repoUrl).toSet
  if anyMavenSuccess then
    retainedMavenVersions(knownModules, mavenModulesByVersion.view.mapValues(_.toSet).toMap, tagVersions) ++ tagVersions
  else
    scala3Jvm.map(_.version).toSet ++ tagVersions

/** Keep Maven versions published by enough modules, or that also exist as git tags. */
private def retainedMavenVersions(
    knownModules: Seq[String],
    mavenModulesByVersion: Map[String, Set[String]],
    taggedVersions: Set[String]
): Set[String] =
  val minModules =
    if knownModules.size <= 1 then 1
    else math.max(2, (knownModules.size + 1) / 2)
  mavenModulesByVersion.iterator.collect {
    case (version, mods)
        if taggedVersions.contains(version) || mods.size >= minModules =>
      version
  }.toSet

private def readCachedProjectModules(project: Project)(using
    driver: CacheDriver[Project, ProjectModules]
): Option[ProjectModules] =
  val dest = driver.dest(project)
  if Files.exists(dest) then
    Some(driver.load(Files.readString(dest), project))
  else None

private def writeCachedProjectModules(pm: ProjectModules)(using
    driver: CacheDriver[Project, ProjectModules]
): Unit =
  val dest = driver.dest(pm.project)
  Files.createDirectories(dest.getParent)
  Files.writeString(dest, driver.write(pm))

/** Cached project modules; refresh when Maven∪git versions diverge from the cache. */
def loadProjectModulesWithVersionCheck(releaseCutOffDate: Option[LocalDate] = None)(
    project: Project
)(using scaladex: Scaladex, driver: CacheDriver[Project, ProjectModules]): AsyncResponse[ProjectModules] =
  scaladex.artifacts(project).flatMap { artifacts =>
    val freshVersions = freshProjectVersions(project, artifacts)
    readCachedProjectModules(project) match
      case Some(cached) if cached.mvs.map(_.version).toSet == freshVersions =>
        Future.successful(cached)
      case Some(cached) =>
        val cachedVersions = cached.mvs.map(_.version).toSet
        val added = freshVersions -- cachedVersions
        val removed = cachedVersions -- freshVersions
        println(
          s"Refreshing project modules for ${project.coordinates} " +
            s"(versions changed: +${added.mkString(", ")} -${removed.mkString(", ")})"
        )
        Future {
          val pm = buildProjectModules(project, artifacts, releaseCutOffDate)
          writeCachedProjectModules(pm)
          pm
        }
      case None =>
        println(s"Refreshing project modules for ${project.coordinates} (no cache)")
        Future {
          val pm = buildProjectModules(project, artifacts, releaseCutOffDate)
          writeCachedProjectModules(pm)
          pm
        }
  }

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
  val project = projectModules.project
  require(
    projectModules.mvs.nonEmpty,
    s"Empty modules list in $project"
  )
  // Published/graph version: newest Maven∪tag candidate (mvs already newest-first).
  // Git revision is resolved later via exact findTag; missing tags keep empty revision
  // rather than falling back to an older tagged release.
  val checkout = projectModules.mvs.head
  val checkoutVersion = checkout.version

  def tryFetchTargets(
      version: String,
      modules: Seq[String]
  ): AsyncResponse[Seq[Target]] =
    val tasks = modules.map { module =>
      def tryFetch(backoffSeconds: Int, attempt: Int): AsyncResponse[Option[Target]] =
        inline def backoff(ex: Throwable, retryable: Boolean) =
          val canRetry = retryable && attempt < MaxMavenInfoAttempts
          val detail = CoordinatorRuntime.describeFailure(ex)
          val action =
            if canRetry then
              s"retry with backoff ${backoffSeconds}s (attempt $attempt/$MaxMavenInfoAttempts)"
            else if retryable then s"giving up after $attempt attempts"
            else "giving up"
          Console.err.println(
            s"Failed to load maven info for $organization/$repository module=$module version=$version ($detail): $action"
          )
          if canRetry then
            SECONDS.sleep(backoffSeconds)
            tryFetch((backoffSeconds * 2).min(60), attempt + 1)
          else Future.successful(None)
        Future({
          val target = cached {
            Maven.asTarget(scalaBinaryVersion, buildConfigSeed)(_)
          }(ModuleVersion(module, version, project))
          Some(target)
        }).recoverWith {
          case ex: UnknownHostException   => backoff(ex, retryable = true)
          case ex: SocketTimeoutException => backoff(ex, retryable = true)
          case ex: HttpStatusException if ex.getStatusCode == 503 =>
            backoff(ex, retryable = true)
          case ex: HttpStatusException if ex.getStatusCode >= 500 =>
            backoff(ex, retryable = true)
          case ex: java.net.ConnectException if ex.getMessage().contains("Operation timed out") =>
            backoff(ex, retryable = true)
          case ex: java.net.http.HttpTimeoutException =>
            backoff(ex, retryable = true)
          case ex: HttpStatusException if ex.getStatusCode == 404 =>
            Future.successful(None)
          case ex: Exception =>
            backoff(ex, retryable = false)
        }
      tryFetch(1, attempt = 1)
    }
    Future.sequence(tasks).map(_.flatten)

  def stubTargets(modules: Seq[String]): Seq[Target] =
    modules.flatMap: module =>
      Maven.asStubTarget(scalaBinaryVersion, buildConfigSeed)(
        ModuleVersion(module, checkoutVersion, project)
      ) match
        case scala.util.Success(target) => Some(target)
        case scala.util.Failure(ex) =>
          Console.err.println(
            s"Failed to resolve stub target for $organization/$repository module=$module " +
              s"(${CoordinatorRuntime.describeFailure(ex)})"
          )
          None

  def tryVersions(remaining: Seq[ModuleInVersion]): AsyncResponse[LoadedProject] =
    remaining match
      case Nil =>
        val stubs = stubTargets(checkout.modules)
        if stubs.isEmpty then
          CoordinatorLog.exclude(
            project,
            "Maven metadata load failed",
            s"@ $checkoutVersion for modules: ${checkout.modules.mkString(", ")}"
          )
        else
          CoordinatorLog.warn(
            project,
            "POM unavailable; using stub targets",
            s"checkout=$checkoutVersion modules=${checkout.modules.mkString(", ")}"
          )
        Future.successful(LoadedProject(project, checkoutVersion, stubs))
      case mv +: rest =>
        tryFetchTargets(mv.version, mv.modules).flatMap { targets =>
          if targets.nonEmpty then
            if mv.version != checkoutVersion then
              CoordinatorLog.warn(
                project,
                "graph POM lags checkout",
                s"checkout=$checkoutVersion graphPom=${mv.version} " +
                  s"loaded ${targets.size}/${mv.modules.size} modules"
              )
            else if targets.size < mv.modules.size then
              val failed =
                mv.modules.filterNot(m =>
                  targets.exists(_.id.name == Maven.scalaArtifactId(m, scalaBinaryVersion))
                )
              CoordinatorLog.warn(
                project,
                "partial Maven load",
                s"@ ${mv.version} loaded ${targets.size}/${mv.modules.size} modules; failed: ${failed.mkString(", ")}"
              )
            Future.successful(LoadedProject(project, checkoutVersion, targets))
          else tryVersions(rest)
        }

  def versionPublishedOnMaven(version: String, modules: Seq[String]): Boolean =
    modules.exists: module =>
      Maven.resolveCoords(project, module, scalaBinaryVersion, buildConfigSeed) match
        case scala.util.Failure(ex) =>
          Console.err.println(
            s"Failed to resolve coords for $organization/$repository module=$module " +
              s"(${CoordinatorRuntime.describeFailure(ex)})"
          )
          false
        case scala.util.Success((groupId, artifactId)) =>
          Maven.listVersions(groupId, artifactId) match
            case Maven.VersionsLookup.Resolved(versions) =>
              versions.exists(_.version == version)
            case Maven.VersionsLookup.NotFound => false
            case Maven.VersionsLookup.Failure(ex) =>
              Console.err.println(
                s"Failed to list Maven versions for $groupId:$artifactId " +
                  s"(${CoordinatorRuntime.describeFailure(ex)})"
              )
              false

  // Prefer POM at checkout version; then older Central versions; finally stubs.
  val pomCandidates =
    checkout +: projectModules.mvs.filter: mv =>
      mv.version != checkoutVersion && versionPublishedOnMaven(mv.version, mv.modules)

  tryVersions(pomCandidates)

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
  if offlineScaladex then
    println("Scaladex offline mode: reusing data/projectModules (or buildConfig.json targets)")
  def loadProject(p: Project): AsyncResponse[CandidateProject] =
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

  def load(
      candidates: LazyList[Future[CandidateProject]]
  ): Future[Seq[Option[LoadedProject]]] = {
    Future
      .traverse(candidates.zipWithIndex) { (getProject, idx) =>
        for
          project <- getProject
          name = s"${project.project.organization}/${project.project.repository}"
          mvnInfo <-
            project match
              case CandidateProject.BuildAll(project) =>
                Future.successful(
                  Some(LoadedProject(project, "HEAD", Seq(Target.BuildAll)))
                )
              case candidate @ CandidateProject.BuildSelected(project, mvs) =>
                if mvs.isEmpty then Future.successful(None)
                else
                  loadMavenInfo(scalaBinaryVersion, buildConfigSeed)(candidate)
                    .map { result =>
                      CoordinatorProgress.mavenInfoLoaded()
                      CoordinatorProgress.setDetail(s"maven #$name")
                      println(s"Loaded Maven info #${idx + 1} for $name")
                      Option(result)
                    }
                    .recover {
                      case ex: org.jsoup.HttpStatusException if ex.getStatusCode() == 404 =>
                        System.err.println(
                          s"Missing Maven info: ${ex.getUrl()}"
                        )
                        None
                    }
        yield mvnInfo
      }
  }

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
  }
    .map(DependencyGraph(scalaBinaryVersion, _))

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
          mvs.copy(modules =
            modules.filter(isTestableModuleName).filter { module =>
              // Filter out modules for given version
              !matchPatternAndLog(s"${p.organization}:$module:$version")
            }
          )
      }
      .filter(_.modules.nonEmpty)
  )
}
