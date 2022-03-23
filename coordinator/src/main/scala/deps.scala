import org.jsoup._
import collection.JavaConverters._
import java.nio.file._
import scala.sys.process._
import scala.concurrent.*
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext.Implicits.global

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
  LazyList
    .from(0)
    .map(load)
    .takeWhile(_.nonEmpty)
    .flatten

case class ModuleInVersion(version: String, modules: Seq[String])

case class ProjectModules(project: Project, mvs: Seq[ModuleInVersion])

private def loadScaladexVersionsMatrix(project: Project) =
  // Scaladex loads data dynamically using JS handlers
  // We use htmlunit to execute handlers and get all the results
  import com.gargoylesoftware.htmlunit
  import htmlunit.*
  import htmlunit.html.HtmlPage
  import htmlunit.javascript.JavaScriptErrorListener
  import project.{org, name}

  object NoopJsErrorListener extends JavaScriptErrorListener {
    override def loadScriptError(page: HtmlPage, url: java.net.URL, ex: Exception): Unit = ()
    override def malformedScriptURL(page: HtmlPage, script: String, reason: java.net.MalformedURLException): Unit = ()
    override def scriptException(page: HtmlPage, reason: ScriptException): Unit = ()
    override def timeoutError(page: HtmlPage, x: Long, y: Long): Unit = ()
  }
  
  val url = s"https://index.scala-lang.org/artifacts/$org/$name"
  val client = WebClient(BrowserVersion.CHROME)
  def setOption(fn: WebClientOptions => Unit) = fn(client.getOptions())
  setOption(_.setJavaScriptEnabled(true))
  setOption(_.setCssEnabled(false))
  setOption(_.setThrowExceptionOnScriptError(false))
  setOption(_.setThrowExceptionOnFailingStatusCode(true))
  setOption(_.setTimeout(15 * 1000))
  // We set window size to trigger loading of all entries
  client.getCurrentWindow.setInnerHeight(Int.MaxValue)
  client.setJavaScriptErrorListener(NoopJsErrorListener)

  val page = client.getPage[HtmlPage](url)
  Jsoup.parse(page.asXml)

def loadScaladexProject(scalaBinaryVersion: String)(project: Project): ProjectModules =
  val binaryVersionSuffix = "_" + scalaBinaryVersion
  val d = loadScaladexVersionsMatrix(project)
  val mvs =
    for
      table <- d.select("tbody").asScala.toSeq
      version <- table.select(".version").asScala.map(_.text())
    yield 
      val modules = 
        for 
          tr <- table.select("tr").asScala 
            if tr.attr("class").split(" ").contains(binaryVersionSuffix)
          name = tr.select(".artifact").get(0).text.trim    
        yield name
      ModuleInVersion(version, modules.toSeq)

  ProjectModules(project, mvs.filter(_.modules.nonEmpty))

case class ModuleVersion(name: String, version: String, p: Project)

val GradleDep = "compile group: '(.+)', name: '(.+)', version: '(.+)'".r

def asTarget(scalaBinaryVersion: String)(mv: ModuleVersion): Target =
  import mv._
  val url = s"https://index.scala-lang.org/${p.org}/${p.name}/${name}/${version}?target=_$scalaBinaryVersion"
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

def loadMavenInfo(scalaBinaryVersion: String)(projectModules: ProjectModules): LoadedProject = 
  import projectModules.project.{name, org}
  val repoName = s"https://github.com/$org/$name.git" 
  require(projectModules.mvs.nonEmpty, s"Empty modules list in ${projectModules.project}")
  val ModuleInVersion(version, modules) =  projectModules.mvs
    .find(v => findTag(repoName, v.version).isRight)
    .getOrElse(projectModules.mvs.head)
  val mvs = modules.map(m => ModuleVersion(m, version, projectModules.project))
  val targets = mvs.map(cached(asTarget(scalaBinaryVersion)))
  LoadedProject(projectModules.project, version, targets)

  /**
   * @param scalaBinaryVersion Scala binary version name (major.minor) or `3` for scala 3 - following scaladex's convention
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
  val optionalStream = cachedSingle("projects.csv")(loadProjects(scalaBinaryVersion))
    .filter(_.stars >= minStarsCount)
    .sortBy(-_.stars)
    .to(LazyList)
    .map(loadProject)
    .map(projectModulesFilter(filterPatterns.map(_.r)))
    .filter(_.mvs.nonEmpty)
  val optional = maxProjectsCount
    .map(_ - required.length)
    .foldLeft(optionalStream)(_.take(_))
  val projects = {
    val loadProjects = Future.traverse(required #::: optional) { project =>
      Future {
        loadMavenInfo(scalaBinaryVersion)(project)
      }
    }
    Await.result(loadProjects, 30.minutes)
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
@main def runDeps = loadDepenenecyGraph(scalaBinaryVersion = "3", minStarsCount = 100, maxProjectsCount = Some(100))
