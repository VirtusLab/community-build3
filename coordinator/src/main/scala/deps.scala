import org.jsoup._
import collection.JavaConverters._
import java.nio.file._
import scala.sys.process._

// TODO scala3 should be more robust
def loadProjects(scalaRelease: String): Seq[Project] = 
  def load(page: Int) = 
    val d = Jsoup.connect(s"https://index.scala-lang.org/search?scalaVersions=scala3&q=&page=$page").get()
    d.select(".list-result .row").asScala.flatMap { e =>
      val texts = e.select("h4").get(0).text().split("/")
      val stars = e.select(".stats [title=Stars]").asScala.map(_.text)
      if texts.isEmpty || stars.isEmpty then None else Some {
        Project(texts.head, texts.drop(1).mkString("/"))(stars.head.toInt)
      }
    }
  (1 to 10000).toStream.map(load).takeWhile(_.nonEmpty).flatten 

case class ModuleInVersion(version: String, modules: Seq[String])

case class ProjectModules(project: Project, mvs: Seq[ModuleInVersion])

def loadScaladexProject(scalaRelease: String)(project: Project): ProjectModules = 
  import project._
  val url = s"https://index.scala-lang.org/artifacts/$org/$name"
  val d = Jsoup.connect(url).get()
  val mvs = 
    for
      table <- d.select("tbody").asScala.toSeq
      version <- table.select(".version").asScala.map(_.text())
    yield 
      val modules = 
        for 
          tr <- table.select("tr").asScala 
            if tr.attr("class").contains(s"supported-scala-version_$scalaRelease")
          name = tr.select(".artifact").get(0).text.trim
        yield name
      ModuleInVersion(version, modules.toSeq)

  ProjectModules(project, mvs.filter(_.modules.nonEmpty))

case class ModuleVersion(name: String, version: String, p: Project)

val GradleDep = "compile group: '(.+)', name: '(.+)', version: '(.+)'".r

def asTarget(scalaBinaryVersionSeries: String)(mv: ModuleVersion): Target =
  import mv._
  val url = s"https://index.scala-lang.org/${p.org}/${p.name}/${name}/${version}?target=_$scalaBinaryVersionSeries"
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
    
  Target(TargetId(o,n), deps.toSeq)

def loadMavenInfo(scalaBinaryVersionSeries: String)(projectModules: ProjectModules): LoadedProject = 
  val ModuleInVersion(version, modules) = projectModules.mvs.head
  val mvs = modules.map(m => ModuleVersion(m, version, projectModules.project))
  val targets = mvs.map(cached(asTarget(scalaBinaryVersionSeries)))
  LoadedProject(projectModules.project, version, targets)

  /**
   * @param scalaBinaryVersionSeries Scala binary version name (major.minor) or `3.x` for scala 3 - following scaladex's convention
  */
def loadDepenenecyGraph(scalaBinaryVersionSeries: String, minStarsCount: Int, maxProjectsCount: Option[Int] = None, requiredProjects: Seq[Project] = Seq.empty): DependencyGraph =
  val projects = maxProjectsCount match
    case Some(maxCount) if maxCount > requiredProjects.length =>
      val loadedProjects = cachedSingle("projects.csv")(loadProjects(scalaBinaryVersionSeries))
      val topLoadedProject = loadedProjects.sortBy(-_.stars)
      requiredProjects ++ topLoadedProject.take(maxCount - requiredProjects.length)
    case _ =>
      requiredProjects

  val rawDependencies = projects.map(cached(loadScaladexProject(scalaBinaryVersionSeries)))
  val withMavenLoaded = rawDependencies.filter(_.mvs.nonEmpty).map(loadMavenInfo(scalaBinaryVersionSeries))
  DependencyGraph(scalaBinaryVersionSeries, withMavenLoaded)

@main def runDeps = loadDepenenecyGraph("3.x", minStarsCount = 100)
