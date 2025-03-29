import org.jsoup._
import scala.jdk.CollectionConverters._
import scala.concurrent.*
import java.time.LocalDate
import java.util.concurrent.TimeUnit.SECONDS
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import Scaladex.ScaladexUrl

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

def loadScaladexProject(releaseCutOffDate: Option[LocalDate] = None)(
    project: Project
)(using scaladex: Scaladex): AsyncResponse[ProjectModules] = {
  for {
    scala3JvmArtifacts <- scaladex
      .artifacts(project)
      .map:
        _.filter:
          _.artifactId match
            case s"${_}_native${_}" => false
            case s"${_}_sjs${_}"    => false
            case s"${_}_3"          => true
            case _                  => false
    artifactsByVersion = scala3JvmArtifacts.groupBy(_.version)
    versionReleaseData <- Future
      .traverse(artifactsByVersion) { case (version, artifacts) =>
        scaladex
          .artifact(artifacts.head)
          .filter: artifact =>
            releaseCutOffDate.forall(_.isAfter(artifact.releaseLocalData))
          .map: artifact =>
            (version, artifact.releaseDate)
      }
      .map(_.toMap)
    orderedVersions = versionReleaseData.toSeq
      .sortBy(-_._2.toEpochSecond()) // releaseDate-epoch-mill descending
      .map(_._1)
    versionModules =
      for version <- orderedVersions
      yield ModuleInVersion(
        version = version,
        modules = artifactsByVersion(version).map(_.artifactId.stripSuffix("_3"))
      )
  } yield ProjectModules(project, versionModules)
}

case class VersionedModules(modules: ModuleInVersion, semVersion: SemVersion)
case class ModuleVersion(name: String, version: String, p: Project)

val GradleDep = "compile group: '(.+)', name: '(.+)', version: '(.+)'".r

def asTarget(scalaBinaryVersion: String)(mv: ModuleVersion): Target =
  import mv._
  val url =
    s"$ScaladexUrl/${p.organization}/${p.repository}/${name}/${version}?target=_$scalaBinaryVersion"
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
    yield Dep(TargetId(groupId.text, artifactId.text), version.text)

  Target(TargetId(o, n), deps.toSeq)

def loadMavenInfo(scalaBinaryVersion: String)(
    projectModules: CandidateProject.BuildSelected
): AsyncResponse[LoadedProject] =
  import projectModules.project.{repository, organization}
  val repoName = s"https://github.com/$organization/$repository.git"
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
          s"Failed to load maven info for $organization/$repository, reason: $reason: retry with backoff ${backoffSeconds}s"
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
          case _: UnknownHostException   => backoff("service not found")
          case _: SocketTimeoutException => backoff("socket timeout exception")
          case ex: HttpStatusException if ex.getStatusCode == 503 =>
            backoff("service unavailable")
          case ex: java.net.ConnectException if ex.getMessage().contains("Operation timed out") =>
            backoff(ex.getMessage())
          case ex: Exception =>
            Console.err.println(
              s"Failed to load maven info for $organization/$repository: ${ex}"
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
    filterPatterns: Seq[String] = Nil,
    releaseCutOffDate: Option[LocalDate] = None
): AsyncResponse[DependencyGraph] =
  given Scaladex = Scaladex()
  val patterns = filterPatterns.map(_.r)
  def loadProject(p: Project): AsyncResponse[CandidateProject] =
    if customProjects.contains(p) then Future.successful(CandidateProject.BuildAll(p))
    else
      cachedAsync { (p: Project) =>
        loadScaladexProject(releaseCutOffDate)(p)
          .map(projectModulesFilter(patterns))
      }(p).map { case ProjectModules(project, mvs) =>
        CandidateProject.BuildSelected(project, mvs)
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
        load(optional(from = continueFrom, limit = Some(remainingSlots)))
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
          mvs.copy(modules = modules.filter { module =>
            // Filter out modules for given version
            !matchPatternAndLog(s"${p.organization}:$module:$version")
          })
      }
      .filter(_.modules.nonEmpty)
  )
}
