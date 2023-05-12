import org.jsoup._
import scala.jdk.CollectionConverters._
import java.nio.file._
import scala.sys.process._
import scala.concurrent.*
import scala.concurrent.duration.*
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit.SECONDS
import java.net.SocketTimeoutException
import java.net.UnknownHostException

import scala.language.implicitConversions

// TODO scala3 should be more robust
def loadProjects(scalaBinaryVersion: String): Seq[StarredProject] =
  val release = scalaBinaryVersion match {
    case "3" => "3.x"
    case v   => v
  }
  val commonSearchParams = Map(
    "languages" -> release,
    "platforms" -> "jvm",
    "sort" -> "stars",
    "q" -> "*"
  ).map(_ + "=" + _).mkString("&")
  def load(page: Int) =
    val d = Jsoup
      .connect(
        s"https://index.scala-lang.org/search?${commonSearchParams}&page=$page"
      )
      .get()
    d.select(".list-result .row").asScala.flatMap { e =>
      val texts = e.select("h4").get(0).text().split("/")
      val stars = e.select(".stats [title=Stars]").asScala.map(_.text)
      Option.unless(texts.isEmpty || stars.isEmpty) {
        StarredProject(texts.head, texts.drop(1).mkString("/"))(stars.head.toInt)
      }
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

def loadScaladexProject(scalaBinaryVersion: String)(
    project: Project
): AsyncResponse[ProjectModules] =
  import util.*
  val binaryVersionSuffix = "_" + scalaBinaryVersion
  Scaladex
    .projectSummary(project.org, project.name, scalaBinaryVersion)
    .flatMap {
      case None =>
        Console.err.println(
          s"No project summary for ${project.org}/${project.name}"
        )
        Future.successful(Nil)
      case Some(projectSummary) =>
        val releaseDates = collection.mutable.Map.empty[String, OffsetDateTime]
        case class VersionRelease(version: String, releaseDate: OffsetDateTime)
        for
          artifactsMetadata <- Future
            .traverse(projectSummary.artifacts) { artifact =>
              Scaladex
                .artifactMetadata(
                  groupId = projectSummary.groupId,
                  artifactId = s"${artifact}_3"
                )
                .map { response =>
                  if (response.pagination.pageCount != 1)
                    Console.err.println(
                      "Scaladex now implementes pagination! Ignoring artifact metadata from additional pages"
                    )
                  // Order versions based on their release date, it should be more stable in case of hash-based pre-releases
                  // Previous approach with sorting SemVersion was not stable and could lead to runtime erros (due to not transitive order of elements)
                  val versions = response.items
                    .tapEach(v => releaseDates += v.version -> v.releaseDate)
                    .map(_.version)
                  artifact -> versions
                }
            }
            .map(_.toMap)
          orderedVersions = projectSummary.versions
            .flatMap(v => releaseDates.get(v).map(VersionRelease(v, _)))
            .sortBy(_.releaseDate)(using summon[Ordering[OffsetDateTime]].reverse)
            .map(_.version)
        yield for version <- orderedVersions
        yield ModuleInVersion(
          version,
          modules = artifactsMetadata.collect {
            case (module, versions) if versions.contains(version) => module
          }.toSeq
        )
    }
    .map { moduleVersions =>
      val modules = moduleVersions
        .filter(_.modules.nonEmpty)
        .map(mvs => VersionedModules(mvs, mvs.version))
        .map(_.modules)
      ProjectModules(project, modules)
    }

case class VersionedModules(modules: ModuleInVersion, semVersion: SemVersion)
case class ModuleVersion(name: String, version: String, p: Project)

val GradleDep = "compile group: '(.+)', name: '(.+)', version: '(.+)'".r

def asTarget(scalaBinaryVersion: String)(mv: ModuleVersion): Target =
  import mv._
  val url =
    s"https://index.scala-lang.org/${p.org}/${p.name}/${name}/${version}?target=_$scalaBinaryVersion"
  val d = Jsoup.connect(url).get()
  val gradle = d.select("#copy-gradle").text()
  val GradleDep(o, n, v) = gradle: @unchecked
  val orgParsed = o.split('.').mkString("/")
  val mCentralUrl =
    s"https://repo1.maven.org/maven2/$orgParsed/$n/$v/$n-$v.pom"
  val md = Jsoup.connect(mCentralUrl).get

  val deps =
    for
      dep <- md.select("dependency").asScala
      groupId <- dep.select("groupId").asScala
      artifactId <- dep.select("artifactId").asScala
      version <- dep.select("version").asScala
      scope = dep.select("scope").asScala.headOption.fold("compile")(_.text())
    yield Dep(TargetId(groupId.text, artifactId.text), version.text)

  Target(TargetId(o, n), deps.toSeq)

def loadMavenInfo(scalaBinaryVersion: String)(
    projectModules: CandidateProject.BuildSelected
): AsyncResponse[LoadedProject] =
  import projectModules.project.{name, org}
  val repoName = s"https://github.com/$org/$name.git"
  require(
    projectModules.mvs.nonEmpty,
    s"Empty modules list in ${projectModules.project}"
  )
  val ModuleInVersion(version, modules) = projectModules.mvs
    .find(v => findTag(repoName, v.version).isDefined)
    .getOrElse(projectModules.mvs.head)

  val tasks = modules.map { module =>
    def tryFetch(backoffSeconds: Int): AsyncResponse[Option[Target]] = {
      inline def backoff(reason: => String) = {
        Console.err.println(
          s"Failed to load maven info for $org/$name, reason: $reason: retry with backoff ${backoffSeconds}s"
        )
        SECONDS.sleep(backoffSeconds)
        tryFetch((backoffSeconds * 2).min(60))
      }
      Future({
        val target = cached {
          asTarget(scalaBinaryVersion)(_)
        }(ModuleVersion(module, version, projectModules.project))
        Some(target)
      })
        .recoverWith {
          case ex: UnknownHostException   => backoff("service not found")
          case ex: SocketTimeoutException => backoff("socket timeout exception")
          case ex: HttpStatusException if ex.getStatusCode == 503 =>
            backoff("service unavailable")
          case ex: Exception =>
            Console.err.println(
              s"Failed to load maven info for $org/$name: ${ex}"
            )
            Future.successful(None)
        }
    }
    tryFetch(1)
  }

  Future
    .foldLeft(tasks)(List.empty[Target]) { case (acc, target) =>
      acc ::: target.toList
    }
    .map(LoadedProject(projectModules.project, version, _))

  /** @param scalaBinaryVersion
    *   Scala binary version name (major.minor) or `3` for scala 3 - following scaladex's convention
    */
def loadDepenenecyGraph(
    scalaBinaryVersion: String,
    minStarsCount: Int,
    maxProjectsCount: Option[Int] = None,
    requiredProjects: Seq[Project] = Nil,
    customProjects: Seq[Project] = Nil,
    filterPatterns: Seq[String] = Nil
): AsyncResponse[DependencyGraph] =
  val patterns = filterPatterns.map(_.r)
  def loadProject(p: Project): AsyncResponse[CandidateProject] =
    if customProjects.contains(p) then Future.successful(CandidateProject.BuildAll(p))
    else
      cachedAsync { (p: Project) =>
        loadScaladexProject(scalaBinaryVersion)(p)
          .map(projectModulesFilter(patterns))
      }(p).map { case ProjectModules(project, mvs) => CandidateProject.BuildSelected(project, mvs) }

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
          name = s"${project.project.org}/${project.project.name}"
          mvnInfo <-
            project match
              case CandidateProject.BuildAll(project) =>
                Future.successful(Some(LoadedProject(project, "HEAD", Seq(Target.BuildAll))))
              case candidate @ CandidateProject.BuildSelected(project, mvs) =>
                if mvs.isEmpty
                then Future.successful(None)
                else
                  loadMavenInfo(scalaBinaryVersion)(candidate)
                    .map { result =>
                      println(s"Loaded Maven info #${idx + 1} for $name")
                      Option(result)
                    }
                    .recover {
                      case ex: org.jsoup.HttpStatusException if ex.getStatusCode() == 404 =>
                        System.err.println(s"Missing Maven info: ${ex.getUrl()}")
                        None
                    }
        yield mvnInfo
      }
  }

  load(
    required #::: optional(
      from = 0,
      limit = maxProjectsCount.map(_ - requiredProjects.length - customProjects.length).map(_ max 0)
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
        val toLoad = remainingSlots + (remainingSlots * 0.5).toInt.max(10).min(50)
        println(s"Filling remaining ${remainingSlots} slots, trying to load $toLoad next projects")
        load(optional(from = continueFrom, limit = Some(remainingSlots)))
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
            if !matchPatternAndLog(s"${p.org}:${p.name}:$version") =>
          mvs.copy(modules = modules.filter { module =>
            // Filter out modules for given version
            !matchPatternAndLog(s"${p.org}:$module:$version")
          })
      }
      .filter(_.modules.nonEmpty)
  )
}
