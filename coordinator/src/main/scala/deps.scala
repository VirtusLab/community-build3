import org.jsoup._
import collection.JavaConverters._
import java.nio.file._
import scala.sys.process._
import scala.concurrent.*
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext.Implicits.global

// TODO scala3 should be more robust
def loadProjects(scalaBinaryVersion: String): Seq[Project] =
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
      .connect(s"https://index.scala-lang.org/search?${commonSearchParams}&page=$page")
      .get()
    d.select(".list-result .row").asScala.flatMap { e =>
      val texts = e.select("h4").get(0).text().split("/")
      val stars = e.select(".stats [title=Stars]").asScala.map(_.text)
      Option.unless(texts.isEmpty || stars.isEmpty) {
        Project(texts.head, texts.drop(1).mkString("/"))(stars.head.toInt)
      }
    }
  LazyList
    .from(1) // page 0 and page 1 have the same content
    .map(load)
    .takeWhile(_.nonEmpty)
    .flatten

case class ModuleInVersion(version: String, modules: Seq[String])
case class ProjectModules(project: Project, mvs: Seq[ModuleInVersion])

def loadScaladexProject(scalaBinaryVersion: String)(project: Project): ProjectModules =
  import scala.concurrent.ExecutionContext.Implicits.global
  import util.*
  val binaryVersionSuffix = "_" + scalaBinaryVersion
  val moduleVersionsTask = Scaladex
    .projectSummary(project.org, project.name, scalaBinaryVersion)
    .flatMap {
      case None =>
        System.err.println(s"No project summary for ${project.org}/${project.name}")
        Future.successful(Nil)
      case Some(projectSummary) =>
        for artifactsMetadata <- Future
            .traverse(projectSummary.artifacts) { artifact =>
              Scaladex
                .artifactMetadata(groupId = projectSummary.groupId, artifactId = s"${artifact}_3")
                .map { response =>
                  if (response.pagination.pageCount != 1)
                    System.err.println(
                      "Scaladex now implementes pagination! Ignoring artifact metadata from additional pages"
                    )
                  val versions = response.items.map(_.version)
                  artifact -> versions
                }
            }
            .map(_.toMap)
        yield for version <- projectSummary.versions
        yield ModuleInVersion(
          version,
          modules = artifactsMetadata.collect {
            case (module, versions) if versions.contains(version) => module
          }.toSeq
        )
    }
  val moduleVersions = Await.result(moduleVersionsTask, 5.minute)

  // Make sure that versions are ordered, some libraries don't have correct order in scaladex matrix
  // Eg. scalanlp/breeze lists versions: 2.0, 2.0-RC1, 2.0.1-RC2, 2.0.1-RC1
  // We want to have latests versions in front of collection
  case class VersionedModules(modules: ModuleInVersion, semVersion: SemVersion)
  val modules = moduleVersions
    .filter(_.modules.nonEmpty)
    .map(mvs => VersionedModules(mvs, mvs.version))
    .sortBy(_.semVersion)
    .map(_.modules)
  ProjectModules(project, modules)

case class ModuleVersion(name: String, version: String, p: Project)

val GradleDep = "compile group: '(.+)', name: '(.+)', version: '(.+)'".r

def asTarget(scalaBinaryVersion: String)(mv: ModuleVersion): Target =
  import mv._
  println(version -> scalaBinaryVersion)
  val url =
    s"https://index.scala-lang.org/${p.org}/${p.name}/${name}/${version}?target=_$scalaBinaryVersion"
  val d = Jsoup.connect(url).get()
  val gradle = d.select("#copy-gradle").text()
  println(gradle)
  val GradleDep(o, n, v) = gradle
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

def loadMavenInfo(scalaBinaryVersion: String)(projectModules: ProjectModules): LoadedProject =
  import projectModules.project.{name, org}
  val repoName = s"https://github.com/$org/$name.git"
  require(projectModules.mvs.nonEmpty, s"Empty modules list in ${projectModules.project}")
  val ModuleInVersion(version, modules) = projectModules.mvs
    .find(v => findTag(repoName, v.version).isRight)
    .getOrElse(projectModules.mvs.head)
  val toTargetsTask = Future.traverse(modules) { module =>
    Future {
      cached {
        asTarget(scalaBinaryVersion)(_)
      }(ModuleVersion(module, version, projectModules.project))
    }
      .map(Some(_))
      .recover { case ex: Exception =>
        println(ex)
        None
      }
  }
  val targets = Await.result(toTargetsTask, 5.minute).flatten
  LoadedProject(projectModules.project, version, targets)

  /** @param scalaBinaryVersion
    *   Scala binary version name (major.minor) or `3` for scala 3 - following scaladex's convention
    */
def loadDepenenecyGraph(
    scalaBinaryVersion: String,
    minStarsCount: Int,
    maxProjectsCount: Option[Int] = None,
    requiredProjects: Seq[Project] = Nil,
    filterPatterns: Seq[String] = Nil
): DependencyGraph =
  def loadProject(p: Project) = cached(loadScaladexProject(scalaBinaryVersion))(p)

  val required = LazyList
    .from(requiredProjects)
    .map(loadProject)
  val ChunkSize = 32
  val optionalStream = cachedSingle("projects.csv")(loadProjects(scalaBinaryVersion))
    .takeWhile(_.stars >= minStarsCount)
    .sliding(ChunkSize, ChunkSize)
    .to(LazyList)
    .zipWithIndex
    .flatMap { (chunk, idx) =>
      println(s"Load projects - chunk #${idx}, projects indexes from ${idx * ChunkSize}")
      val calcChunk = Future
        .traverse(chunk) { project =>
          Future {
            loadProject
              .andThen(projectModulesFilter(filterPatterns.map(_.r)))
              .apply(project)
          }
        }
        .map(_.filter(_.mvs.nonEmpty))
      Await.result(calcChunk, 5.minute)
    }
  val optional = maxProjectsCount
    .map(_ - required.length)
    .foldLeft(optionalStream)(_.take(_))
  val projects = {
    val loadProjects = Future.traverse((required #::: optional).zipWithIndex) { (project, idx) =>
      Future {
        val name = s"${project.project.org}/${project.project.name}"
        println(
          s"Load Maven info: #${idx}${maxProjectsCount.fold("")("/" + _)} - $name"
        )
        Option(loadMavenInfo(scalaBinaryVersion)(project))
      }.recover {
        case ex: org.jsoup.HttpStatusException if ex.getStatusCode() == 404 =>
          System.err.println(s"Missing Maven info: ${ex.getUrl()}")
          None
      }
    }
    Await.result(loadProjects, 30.minutes).flatten
  }
  DependencyGraph(scalaBinaryVersion, projects)

def projectModulesFilter(
    filterPatterns: Seq[util.matching.Regex]
)(project: ProjectModules): ProjectModules = {
  val p = project.project
  def matchPatternAndLog(v: String): Boolean = {
    filterPatterns
      .find(_.matches(v))
      .tapEach { pattern => println(s"Excluding entry $v, matched by pattern ${pattern.regex}") }
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
@main def runDeps =
  loadDepenenecyGraph(scalaBinaryVersion = "3", minStarsCount = 100, maxProjectsCount = Some(100))
