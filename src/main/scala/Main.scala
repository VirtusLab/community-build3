import org.jsoup._
import collection.JavaConverters._
import java.nio.file._

case class Project(org: String, name: String, stars: Int):
  def show = s"$org%$name%$stars"

object Project:
  def load(line: String) = 
    val splitted = line.split("%")
    Project(splitted(0), splitted(1), splitted(2).toInt)

def md(org: String, name: String, version: String, scope: String = "compile"): MD = 
  s"$org%$name%$version%$scope"

val scalaVersion = "3.0.0-M3"

type MD = String

case class Art(name: String, version: String):
  def show = s"$name%$version"

object Art:
  def load(s: String) = 
    val d = s.split("%")
    Art(d(0), d(1))

val projectsF = Paths.get("data/projects.txt")

def artFile(p: Project) = Paths.get(s"data/arts/${p.org}_${p.name}.txt")

case class MvnMapping(name: String, version: String, mvn: String, deps: Seq[String]):
  def show = (Seq(name, version, mvn) ++ deps).mkString(",")

object MvnMapping:
  def load(s: String) =
    val d = s.split(",")
    MvnMapping(d(0), d(1),d(2),d.drop(3))

def mdMappingFile(p: Project) = Paths.get(s"data/mvn/${p.org}_${p.name}.txt")

val GradleDep = "compile group: '(.+)', name: '(.+)', version: '(.+)'".r

def deps(art: Art, p: Project): MvnMapping =
  println(art -> p)
  val url = s"https://index.scala-lang.org/${p.org}/${p.name}/${art.name}/${art.version}?target=_$scalaVersion"
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
    yield s"${groupId.text}%${artifactId.text}%${version.text}%$scope"
    
  MvnMapping(art.name, art.version, s"$o%$n%$v", deps.toSeq)

@main def listProject: Unit = 
  def load(page: Int) = 
    val d = Jsoup.connect(s"https://index.scala-lang.org/search?scalaVersions=scala3&q=&page=$page").get()
    d.select(".list-result .row").asScala.flatMap { e =>
      val texts = e.select("h4").get(0).text().split("/")
      val stars = e.select(".stats [title=Stars]").asScala.map(_.text)
      if texts.isEmpty || stars.isEmpty then None else Some {
        Project(texts.head, texts.drop(1).mkString("/"), stars.head.toInt)
    }
  }
  Files.write(projectsF, (1 to 40).flatMap(p => load(p).map(_.show)).mkString("\n").getBytes)


@main def listVersionsAndArts: Seq[(Project, Seq[(String, Seq[Art])])] =
  if !Files.exists(projectsF) then listProject
  val projects = Files.readAllLines(projectsF).asScala.map(Project.load)

  def modules(proj: Project): Seq[(String, Seq[Art])] = 
    import proj._
    val url = s"https://index.scala-lang.org/artifacts/$org/$name"
    val d = Jsoup.connect(url).get()
    for
      table <- d.select("tbody").asScala.toSeq
      version <- table.select(".version").asScala.take(1).map(_.text())
    yield 
      val arts = 
        for 
          tr <- table.select("tr").asScala 
            if tr.attr("class").contains(s"supported-scala-version_$scalaVersion")
          name = tr.select(".artifact").get(0).text.trim
        yield Art(name, version)
      (version, arts.toSeq)
    
  projects.map { p =>
    val pFile = artFile(p)
    val arts = 
      if Files.exists(pFile) then 
        Files.readAllLines(pFile).asScala.map{ l =>
          val d = l.split(",").toList
          d.head -> d.tail.map(Art.load)
        }.toList
      else 
        val res = modules(p).filter(_._2.nonEmpty)
        Files.write(pFile, res.map(x => (x._1 +: x._2.map(_.show)).mkString(",")).mkString("\n").getBytes)
        res
    p -> arts
  }.toSeq

@main def mavenMappings: Seq[(Project, Seq[MvnMapping])] =
  val mappings = listVersionsAndArts
  mappings.map{ case (p, versions) =>
    val f = mdMappingFile(p)
    val current = 
      if !Files.exists(f) then Nil 
      else Files.readAllLines(f).asScala.filter(_.nonEmpty).map(MvnMapping.load).toSeq

    val covered = current.map(m => m.name -> m.version)

    val loaded = for 
      (v, arts) <- versions.take(1)
      a <- arts if !covered.contains(a.name -> v)
    yield deps(a, p)

    val all = current ++ loaded
    if loaded.nonEmpty then Files.write(f, all.map(_.show).mkString("\n").getBytes)
    p -> all
  }

enum ProblemKind:
  case DifferentVersion(id: String, expected: String, defined: String)
  case Missing(id: String, v: String)


case class Problem(org: String, name: String, project: String, v: String, kind: ProblemKind)

@main def findDifferentVersion =
  def extract(s: String): (String, String) = 
    val d = s.split("%")
    (d(0) + "%" + d(1), d(2))

  val data = mavenMappings
  val myOrgs = data.flatMap(_._2).map{ d => extract(d.mvn) }
  val defined = myOrgs.toMap

  val problems = 
    for
      (p, mvns) <- data
      m <- mvns
      d <- m.deps 
      (id, v) = extract(d) 
    yield 
      val kind: Option[ProblemKind] = defined.get(id) match
        case Some(dv) =>
          if v != dv then 
            Some(ProblemKind.DifferentVersion(id, v, dv))
          else None
        case None =>
          if id.endsWith("_3.0.0-M3") then Some(ProblemKind.Missing(id, v))
          else None
      kind.map(Problem(p.org, p.name, m.name, m.version, _))

  val diff = problems.flatten.map(_.kind).distinct.collect{ case a: ProblemKind.DifferentVersion => a}
  
  diff.groupBy(_.id).map{ case (id, problems) =>
    val niceId = id.replace("%", "/").stripSuffix("_" + scalaVersion)
    println(s"$niceId disted as ${defined(id)} but used as: ${problems.map(_.expected).mkString(", ")} ")
  }
// @main def run: Unit = 
//   import sttp.client3._
  
  


//   def modules(proj: Project): Seq[(String, Seq[MD])] = 
//     import proj._
//     val url = s"https://index.scala-lang.org/artifacts/$org/$name"
//     val d = Jsoup.connect(url).get()
//     for
//       table <- d.select("tbody").asScala.toSeq
//       version <- table.select(".version").asScala.take(1).map(_.text())
//       tr <- table.select("tr").asScala 
//         if tr.attr("class").contains(s"supported-scala-version_$scalaVersion")
//       name = tr.select(".artifact").get(0).text.trim
//     yield Art(proj.org, proj.name, name, version)

//   



//     val deps = .toList
//     println(deps)


//   // println(modules(Project("typelevel", "cats", 0)))
//   deps(Art("typelevel", "cats", "cats-core", "2.4.1"))
  