// import org.jsoup._
// import collection.JavaConverters._
// import java.nio.file._



// def md(org: String, name: String, version: String, scope: String = "compile"): MD = 
//   s"$org%$name%$version%$scope"

// val scalaVersion = "3.0.0-RC3"
// val scalaSuffix = "_" + scalaVersion

// type MD = String

// case class Art(name: String, version: String):
//   def show = s"$name%$version"

// object Art:
//   def load(s: String) = 
//     val d = s.split("%")
//     Art(d(0), d(1))

// val projectsF = Paths.get("data/projects.txt")

// def artFile(p: Project) = Paths.get(s"data/arts/${p.org}_${p.name}.txt")



// def mdMappingFile(p: Project) = Paths.get(s"data/mvn/${p.org}_${p.name}.txt")

// def deps(art: Art, p: Project): MvnMapping =
//   println(art -> p)
//   val url = s"https://index.scala-lang.org/${p.org}/${p.name}/${art.name}/${art.version}?target=_$scalaVersion"
//   val d = Jsoup.connect(url).get()
//   val gradle = d.select("#copy-gradle").text()
//   println(gradle)
//   val GradleDep(o, n, v) = gradle
//   val orgParsed = o.split('.').mkString("/")
//   val mCentralUrl = 
//     s"https://repo1.maven.org/maven2/$orgParsed/$n/$v/$n-$v.pom"
//   val md = Jsoup.connect(mCentralUrl).get

//   val deps = 
//     for
//       dep <- md.select("dependency").asScala
//       groupId <- dep.select("groupId").asScala
//       artifactId <- dep.select("artifactId").asScala
//       version <- dep.select("version").asScala
//       scope = dep.select("scope").asScala.headOption.fold("compile")(_.text())
//     yield s"${groupId.text}%${artifactId.text}%${version.text}%$scope"
    
//   MvnMapping(art.name, art.version, s"$o%$n%$v", deps.toSeq)

// @main def listProject: Unit = 
//   def load(page: Int) = 
//     val d = Jsoup.connect(s"https://index.scala-lang.org/search?scalaVersions=scala3&q=&page=$page").get()
//     d.select(".list-result .row").asScala.flatMap { e =>
//       val texts = e.select("h4").get(0).text().split("/")
//       val stars = e.select(".stats [title=Stars]").asScala.map(_.text)
//       if texts.isEmpty || stars.isEmpty then None else Some {
//         Project(texts.head, texts.drop(1).mkString("/"))(stars.head.toInt)
//     }
//   }
//   Files.write(projectsF, (1 to 40).flatMap(p => load(p).map(_.show)).mkString("\n").getBytes)


// @main def listVersionsAndArts: Seq[(Project, Seq[(String, Seq[Art])])] =
//   if !Files.exists(projectsF) then listProject
//   val projects = Files.readAllLines(projectsF).asScala.map(Project.load)

//   def modules(proj: Project): Seq[(String, Seq[Art])] = 
//     import proj._
//     val url = s"https://index.scala-lang.org/artifacts/$org/$name"
//     val d = Jsoup.connect(url).get()
//     for
//       table <- d.select("tbody").asScala.toSeq
//       version <- table.select(".version").asScala.take(1).map(_.text())
//     yield 
//       val arts = 
//         for 
//           tr <- table.select("tr").asScala 
//             if tr.attr("class").contains(s"supported-scala-version_$scalaVersion")
//           name = tr.select(".artifact").get(0).text.trim
//         yield Art(name, version)
//       (version, arts.toSeq)
    
//   projects.map { p =>
//     val pFile = artFile(p)
//     val arts = 
//       if Files.exists(pFile) then 
//         Files.readAllLines(pFile).asScala.map{ l =>
//           val d = l.split(",").toList
//           d.head -> d.tail.map(Art.load)
//         }.toList
//       else 
//         val res = modules(p).filter(_._2.nonEmpty)
//         Files.write(pFile, res.map(x => (x._1 +: x._2.map(_.show)).mkString(",")).mkString("\n").getBytes)
//         res
//     p -> arts
//   }.toSeq

// @main def mavenMappings: Seq[(Project, Seq[MvnMapping])] =
//   val mappings = listVersionsAndArts
//   mappings.map{ case (p, versions) =>
//     val f = mdMappingFile(p)
//     val current = 
//       if !Files.exists(f) then Nil 
//       else Files.readAllLines(f).asScala.filter(_.nonEmpty).map(MvnMapping.load).toSeq

//     val covered = current.map(m => m.name -> m.version)

//     val loaded = for 
//       (v, arts) <- versions.take(1) // take only most recent version
//       a <- arts if !covered.contains(a.name -> v)
//     yield deps(a, p)

//     val all = current ++ loaded
//     if loaded.nonEmpty then Files.write(f, all.map(_.show).mkString("\n").getBytes)
//     p -> all
//   }

// @main def runPlan =
//   val data = mavenMappings.filter{p => p._2.nonEmpty}
//   val toLevelData = data.filter(_._1.stars > 20)
//   val targetMap = 
//     (for 
//       (p, mapping) <- data
//       mvn <- mapping
//     yield mvn.mvn.split('%').take(2).mkString("%") -> p
//     ).toMap

//   case class ProjectVersion(p: Project, v: String)

//   def depsFrom(data: Seq[MvnMapping]): Seq[ProjectVersion] =
//     data.flatMap(_.deps).flatMap{ m =>
//       val d = m.split("%")
//       val id = d(0)+"%"+d(1)
//       if !id.endsWith(scalaSuffix) then None
//       else targetMap.get(id) match 
//         case None => 
//           println("No find dep: " + id)
//           None
//         case Some(p) =>
//           Some(ProjectVersion(p, d(2)))  
//     }.distinct

//   val projectsDeps = toLevelData.map{ case (p, targets) => p -> depsFrom(targets)}.toMap

//   val depScore = projectsDeps.flatMap(_._2).groupBy(identity)
//     .map{ case (pv, c) => pv -> c.size}.toMap

//   val topLevelPV = toLevelData.map { case (p, targets) =>
//     val version = targets.map(_.version).distinct match 
//       case Seq(v) => v
//       case Nil => ???
//       case versions =>
//         println(s"Project $p defines multiple versions: $versions")
//         versions.head

//     ProjectVersion(p, version)
//   }

//   val allPVs = (topLevelPV ++ depScore.keys).distinct
  
//   def majorMinor(v: String) = v.split('.').take(2).mkString(".")
//   def patch(pv: ProjectVersion) = pv.v.split('.').drop(2).mkString(".")
//   val overrides = allPVs.groupBy(pv => (pv.p, majorMinor(pv.v))).flatMap { case ((p, mm), pvs) =>
//     val oVersion =
//       if pvs.size == 1 then pvs.head.v
//       else 
//         val v = pvs.maxBy(patch).v
//         println(s"Forcing version for $p to $v from: " + pvs.map(_.v))
//         v
//     pvs.map(_ -> ProjectVersion(p, oVersion + "-community_build"))
//   }.toMap


//   val toBuild = overrides.values.toSeq.distinct.sortBy(_.p.stars).reverse
//   println(s"Will build: (${topLevelPV.size} original and ${toBuild.size} total)")
//   println(toBuild.mkString("\n"))

//   case class ToBuild(pv: ProjectVersion, deps: Seq[ProjectVersion]):
//     def resolve(compiled: Set[ProjectVersion]) = copy(deps = deps.filterNot(compiled))

//   @annotation.tailrec def step(
//     built: Seq[Seq[ProjectVersion]], 
//     toComplete: Seq[ToBuild]): Seq[Seq[ProjectVersion]] =
//       if toComplete.isEmpty then built
//       else 
//         val (completed, todo) = toComplete.partition(_.deps.isEmpty)
//         val actualCompleted = if completed.nonEmpty then completed.map(_.pv) else 
//           println("Cycle in:\n" + toComplete.mkString("\n"))
//           ???
        
//         val builtSet = (built.flatten ++ actualCompleted).toSet
//         println("Compiled: " + actualCompleted)
//         step(actualCompleted +: built, todo.map(_.resolve(builtSet)))
  
//   val (leafs, deps) = toBuild.partition(depScore.get(_).fold(false)(_ == 0))

//   val pvds = toBuild.map(pv =>
//     ToBuild(pv, projectsDeps.getOrElse(pv.p, Nil).filter(_.p != pv.p).map(overrides))
//   )

//   val depsSetps = step(Nil, pvds)
//   val builtSteps = leafs +: depsSetps
  
//   val niceSteps = builtSteps.reverse.zipWithIndex.map { case (pvs, nr) =>
//     val items = 
//       pvs.sortBy(- _.p.stars).map(pv => "  " + pv.p.org + "/" + pv.p.name + " @ " + pv.v.stripSuffix("-community_build"))
//     items.mkString(s"Step ${nr + 1}:\n", "\n", "\n") 
//   }

//   println("Buildplan:\n")
//   println(niceSteps.mkString("\n"))






// @main def findDifferentVersion =
//   def extract(s: String): (String, String) = 
//     val d = s.split("%")
//     (d(0) + "%" + d(1), d(2))

//   val data = mavenMappings
//     // .filter(_._1.stars > 20)
//   val myOrgs = data.flatMap(_._2).map{ d => extract(d.mvn) }
//   val defined = myOrgs.toMap

//   def onlyMinor(v: String, v2: String) = 
//     v.split('.').take(2).mkString(".") == v2.split('.').take(2).mkString(".")


//   val mavensMap = (for {
//     (p, mvns) <- data
//     m <- mvns
//   } yield (extract(m.mvn)._1, p.name)).toMap

//   val mavensSet = data.flatMap(_._2).map(m => extract(m.mvn)._1).toSet
//   val deps = 
//     for
//       (p, mvns) <- data if p.stars > 40
//       m <- mvns
//       d <- m.mvn +: m.deps 
//       (id, v) = extract(d) if mavensSet.contains(id)
//     yield (id, v)


//   val groupped: Seq[(String, Seq[(String, Int)])] = 
//     deps.groupBy(d => mavensMap(d._1)).map { case (n, deps) =>
//       n -> deps.groupBy(_._2.split('.').take(2).mkString(".")).map{ case (v, e) => e.head._2 -> e.size }.toSeq
//     }.toSeq // .filter(_._2.size > 1)

//   val lines = groupped.map {case (name, versions) =>
//     val versionCounnts = versions.map{ case (v, c) => s"$v [$c]"}.mkString(", ")
//     s"$name: $versionCounnts"
//   }

//   val toBuild = groupped.map(_._2.size).sum

//   val projTargets = data.toMap

//   def testProjectVersion(p: Project, version: String) =
//     val outDir = Paths.get("ws").resolve(p.name + "_" + version)
//     val repoDir = outDir.resolve("repo")

//     import scala.sys.process._

//     Seq("rm", "-rf", outDir.toString).!
//     Seq("mkdir", outDir.toString).!

//     def runAndLog(cmds: ProcessBuilder*) = cmds.zipWithIndex.find{ case (cmd, i) =>
//       println("Running " + cmd)
//       val res = cmd.! == 0
//       if res then println(s"Process $cmd succeeded!") else println(s"Process $cmd failed!")
//       !res
//     }

//     def tryTag(pref: String) =
//       import scala.sys.process._
//       val ref = s"https://api.github.com/repos/${p.org}/${p.name}/releases/tags/$pref$version"
//       println(ref)
//       val proc = Process(Seq("curl", "--fail", ref)) #> outDir.resolve("curl_" + pref +".log").toFile
//       proc.! == 0
//     Some("v") match 
//       case None => 
//         println(s"ERROR: not tag for $version in $p") 
//       case Some(tag) =>
//         val targetsCmds = 
//         projTargets(p).map { m => m.mvn.split('%').take(2).mkString("%").stripSuffix("_" + scalaVersion) } // TODO!

//         val repo = s"git@github.com:${p.org}/${p.name}.git"
        
//         val cloneCmd = Seq("git", "clone",repo, repoDir.toString, "-b", tag + version, "--depth", "1")
//         val sbtCommand = 
//         Seq("sbt", "++3.0.0-M3", s"-Dcommunitybuild.version=$version-community", "runBuild " + targetsCmds.mkString(" ")) 
//         runAndLog(
//           Process(cloneCmd) #> outDir.resolve("clone.log").toFile, 
//           Process(Seq("ln", "-s", 
//             "/home/krzysiek/workspace/community-build3/resources/CommunityBuildPlugin.scala", 
//             repoDir.resolve("project/CommunityBuildPlugin.scala").toString)),
//           Process(sbtCommand, Some(repoDir.toFile)) #> outDir.resolve("sbt.log").toFile
//         )

//   println(s"All projects ${groupped.size} to build: ${toBuild}")

//   val pv = groupped.map{ case (n, versions) => 
//     val p = data.find(_._1.name == n).get._1
//     p -> versions
//   }

//   for 
//     (p, versions) <- pv.take(2)
//     (v, _) <- versions
//   do testProjectVersion(p, v)
