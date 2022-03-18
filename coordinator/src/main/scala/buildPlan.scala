import org.jsoup._
import collection.JavaConverters._
import java.nio.file._
import java.io.FileNotFoundException
import java.net.URL
import scala.sys.process._
import scala.util.CommandLineParser.FromString
import scala.util.Try
import com.typesafe.config.ConfigFactory
import pureconfig._
import pureconfig.error.*

val TagRef = """.+refs\/tags\/(.+)""".r

def findTag(repoUrl: String, version: String): Either[String, String] = 
  val cmd = Seq("git", "ls-remote", "--tags", repoUrl)
  Try {
    val lines = cmd.!!.linesIterator.filter(_.contains(version)).toList
    lines.collectFirst { case TagRef(tag) => tag } match
        case Some(tag) => Right(tag)
        case _ => Left(s"No tag in:\n${lines.map("-" + _ +"_").mkString("\n")}")
  }.toEither.left.map { e =>
    e.printStackTrace()
    e.getMessage
  }.flatten

object WithExtractedScala3Suffix {
  def unapply(s: String): Option[(String, String)] = {
    val parts = s.split("_")
    if (parts.length > 1 && parts.last.startsWith("3")) {
      Some(parts.init.mkString("_"), parts.last)
    } else {
      None
    }
  }
}

def hasScala3Suffix(s: String) = s match {
  case WithExtractedScala3Suffix(_, _) => true
  case _ => false
}

// Needed as long as some projects use scala version prior to 3.0.0
def replaceScalaBinaryVersion(s: String) = s match { case WithExtractedScala3Suffix(prefix, _) => prefix + "_3" }

def stripScala3Suffix(s: String) = s match { case WithExtractedScala3Suffix(prefix, _) => prefix }

def buildPlanCommons(depGraph: DependencyGraph) = 
  val data = depGraph.projects
  val topLevelData = data.filter(_.p.stars > 100)

  val fullInfo = data.map(l => l.p -> l).toMap

  // TODO we assume that targets does not change between version, and this may not be true...
  val depsMap: Map[TargetId, ProjectVersion] = 
    data.flatMap(lp => lp.targets.map(t => t.id -> ProjectVersion(lp.p, lp.v))).toMap

  def flattenScalaDeps(p: LoadedProject): Seq[ProjectVersion] =
    p.targets.flatMap(_.deps.filter(dep => hasScala3Suffix(dep.id.name))).distinct.flatMap(
      d => depsMap.get(d.id).map(_.copy(v = d.version))
    )

  val projectsDeps: Map[ProjectVersion, Seq[ProjectVersion]] = 
    topLevelData.map(lp => ProjectVersion(lp.p, lp.v) -> flattenScalaDeps(lp)).toMap

  (topLevelData, fullInfo, projectsDeps)

def makeStepsBasedBuildPlan(depGraph: DependencyGraph): BuildPlan =
  val (topLevelData, fullInfo, projectsDeps) = buildPlanCommons(depGraph)

  val depScore = projectsDeps.flatMap(_._2).groupBy(identity)
    .map{ case (pv, c) => pv -> c.size}.toMap

  val topLevelPV = topLevelData.map(lp => ProjectVersion(lp.p, lp.v))
  val allPVs = (topLevelPV ++ depScore.keys).distinct
  
  def majorMinor(v: String) = v.split('.').take(2).mkString(".")
  def patch(pv: ProjectVersion) = pv.v.split('.').drop(2).mkString(".")
  val overrides = allPVs.groupBy(pv => (pv.p, majorMinor(pv.v))).flatMap { case ((p, mm), pvs) =>
    val oVersion =
      if pvs.size == 1 then pvs.head.v
      else 
        val v = pvs.maxBy(patch).v // TODO make sure that we do built requested version!
        println(s"Forcing version for $p to $v from: " + pvs.map(_.v))
        v
    pvs.map(_ -> ProjectVersion(p, oVersion))
  }.toMap

  case class ToBuild(pv: ProjectVersion, deps: Seq[ProjectVersion]):
    def resolve(compiled: Set[ProjectVersion]) = copy(deps = deps.filterNot(compiled))

  val allToBuild = overrides.values.toSeq.distinct.sortBy(_.p.stars).reverse.map (pv =>
    ToBuild(pv, projectsDeps.getOrElse(pv, Nil).filter(_.p != pv.p).map(overrides))
  )

  val (scala3, rawToBuild) = allToBuild.partition(_.pv.p == Project("lampepfl", "dotty")(0))

  val scala3set = scala3.map(_.pv).toSet
  val toBuilds = rawToBuild.map(_.resolve(scala3set))

  println(s"Will build: (${topLevelPV.size} original and ${toBuilds.size} total)")

  @annotation.tailrec def step(
    built: Seq[Seq[ProjectVersion]], 
    toComplete: Seq[ToBuild]): Seq[Seq[ProjectVersion]] =
      if toComplete.isEmpty then built
      else 
        val (completed, rawTodo) = toComplete.partition(_.deps.isEmpty)
        val (actualCompleted, todo) = if completed.nonEmpty then (completed.map(_.pv), rawTodo) else 
          println("Cycle in:\n" + toComplete.mkString("\n"))
          val mostImporant = rawTodo.maxBy(p => depScore.get(p.pv))
          (Seq(mostImporant.pv), rawTodo.filter(_ != mostImporant))  
        
        val builtSet = (built.flatten ++ actualCompleted).toSet
        // println("Compiled: " + actualCompleted)
        step(actualCompleted +: built, todo.map(_.resolve(builtSet)))
  
  val builtSteps = step(scala3.map(_.pv) :: Nil, toBuilds)
  
  type Steps = Seq[Seq[BuildStep]]
  type Overrides = Map[Dep, Dep]
  val init: (Steps, Overrides) = (Nil, Map.empty)

  def isScala(dep: Dep) = dep.id.org == "org.scala-lang" // TODO should be smarter!

  val computedSteps = 
    builtSteps.reverse.foldLeft(init){ case ((steps, overrides), pvs) =>
      def buildStep(pv: ProjectVersion): BuildStep = 
        // This assumes that we've got the same targets across different versions
        val targets = fullInfo(pv.p).targets
        val allOverrides = 
          targets.flatMap(_.deps).distinct.filterNot(isScala).flatMap(overrides.get)
        val publishVersion = depScore.get(pv).map(_ => pv.v + "-communityBuild")
        BuildStep(pv.p, pv.v, publishVersion, targets.map(_.id), allOverrides)
      
      val newSteps = pvs.sortBy(- _.p.stars).map(buildStep)
      val newOverrides = 
        for 
          step <- newSteps
          newVersion <- step.publishVersion.toSeq
          tid <- step.targets
        yield Dep(tid, step.originalVersion) -> Dep(tid, newVersion)

      (steps :+ newSteps, overrides ++ newOverrides)
    }

  BuildPlan(depGraph.scalaRelease, computedSteps._1)

@main def printBuildPlan: BuildPlan = 
  val deps = loadDepenenecyGraph("3", minStarsCount = 100)
  val plan = makeStepsBasedBuildPlan(deps)
  val niceSteps = plan.steps.zipWithIndex.map { case (steps, nr) =>
    val items = 
      def versionMap(step: BuildStep) = 
        step.originalVersion + step.publishVersion.fold("")(" -> " + _)
      def overrides(step: BuildStep) = step.depOverrides match
        case Nil => ""
        case deps => "\n    with " + deps.map(_.asMvnStr).mkString(", ")

      steps.map(step => "  " + step.p.org + "/" + step.p.name + " @ " + versionMap(step) + overrides(step))
    items.mkString(s"Step ${nr + 1}:\n", "\n", "\n") 
  }

  val bp = "Buildplan:\n" + niceSteps.mkString("\n")

  Files.write(Paths.get("data", "bp.txt"), bp.getBytes)
  plan

def makeDependenciesBasedBuildPlan(
    depGraph: DependencyGraph,
    replacedProjectsConfigPath: String,
    internalProjectConfigsPath: String
) =
  val (topLevelData, fullInfo, projectsDeps) = buildPlanCommons(depGraph)

  val dottyProjectName = "lampepfl_dotty"

  val replacementPattern = raw"(\S+)/(\S+) (\S+)/(\S+) ?(\S+)?".r
  val replacements =
    if (!Paths.get(replacedProjectsConfigPath).toFile.exists) Map.empty
    else
      scala.io.Source
        .fromFile(replacedProjectsConfigPath)
        .getLines
        .filter(line => line.nonEmpty && !line.startsWith("#"))
        .map { case replacementPattern(org1, name1, org2, name2, branch) =>
          (org1, name1) -> ((org2, name2), Option(branch))
        }
        .toMap

  def projectRepoUrl(project: Project) =
    val originalCoords = (project.org, project.name)
    val (org, name) = replacements.get(originalCoords).map(_._1).getOrElse(originalCoords)
    s"https://github.com/${org}/${name}.git"

  def getRevision(project: Project) =
    val originalCoords = (project.org, project.name)
    replacements.get(originalCoords).map(_._2).flatten

  def projectName(project: ProjectVersion) =
    val originalCoords = (project.p.org, project.p.name)
    val (org, name) = replacements.get(originalCoords).map(_._1).getOrElse(originalCoords)
    s"${org}_${name}"
  val projectNames = projectsDeps.keys.map(projectName).toList

  lazy val referenceConfig =
    ConfigSource.resources("buildPlan.reference.conf").cursor().flatMap(_.asConfigValue).toOption
  def internalProjectConfigs(projectName: String) = {
    val fallbackConfig = referenceConfig.foldLeft(ConfigFactory.empty)(_.withValue(projectName, _))
    val config = ConfigSource
      .file(internalProjectConfigsPath)
      .withFallback(ConfigSource.fromConfig(fallbackConfig))
      .at(projectName)
      .load[ProjectBuildConfig]

    config.left.foreach {
      case ConfigReaderFailures(
            CannotReadFile(file, Some(_: FileNotFoundException))
          ) =>
        System.err.println("Internal conifg projects not configured")
      case failure =>
        System.err.println(
          s"Failed to decode content of ${internalProjectConfigsPath}, reason: ${failure.prettyPrint(0)}"
        )
    }

    config.toOption
  }

  def projectConfig(
      name: String,
      repoUrl: String,
      tagOrRevision: Option[String]
  ): Option[ProjectBuildConfig] = {
    val revision = tagOrRevision.getOrElse("HEAD")

    def readProjectConfig() = {
      val baseURL = repoUrl.stripSuffix(".git")
      val config = ConfigSource
        .url(new URL(s"$baseURL/raw/$revision/scala3-community-build.conf"))
        .withFallback(ConfigSource.resources("buildPlan.reference.conf"))
        .load[ProjectBuildConfig]

      config.left.foreach {
        case ConfigReaderFailures(
              CannotReadUrl(url, Some(_: java.io.FileNotFoundException))
            ) => ()
        case reason =>
          System.err.println(
            s"Failed to decode community-build config in ${repoUrl}, reason: ${reason}"
          )
      }
      config.toOption
    }

    readProjectConfig()
      .orElse(internalProjectConfigs(name))
      .filter(_ != ProjectBuildConfig.empty)
      .map { config =>
        println(s"Using custom project config for $name: $config")
        config
      }
  }

  projectsDeps.toList.map { (project, deps) =>
    val repoUrl = projectRepoUrl(project.p)
    val tag = getRevision(project.p).orElse(findTag(repoUrl, project.v).toOption)
    val name = projectName(project)
    val dependencies = deps.map(projectName)
      .filter(depName => projectNames.contains(depName) && depName != name && depName != dottyProjectName)
      .distinct
    ProjectBuildDef(
      name = name,
      dependencies = dependencies.toArray,
      repoUrl = repoUrl,
      revision = tag.getOrElse(""),
      version = project.v,
      targets = fullInfo(project.p).targets.map(t => stripScala3Suffix(t.id.asMvnStr)).mkString(" "),
      config = projectConfig(name, repoUrl, tag)
    )
  }.filter(_.name != dottyProjectName).toArray

private given FromString[Seq[Project]] = str =>
  str.split(",").toSeq.filter(_.nonEmpty).map {
    case s"${org}/${name}" => Project(org, name)(Int.MaxValue)
    case _ => throw new IllegalArgumentException
  }

@main def storeDependenciesBasedBuildPlan(
    scalaBinaryVersion: String,
    minStarsCount: Int,
    maxProjectsCount: Int,
    requiredProjects: Seq[Project],
    replacedProjectsConfigPath: String,
    projectsConfigPath: String,
    projectsFilterPath: String
) =
  val filterPatterns =
    if (!Paths.get(projectsFilterPath).toFile.exists) Nil
    else
      io.Source
        .fromFile(projectsFilterPath)
        .getLines
        .map(_.trim())
        .filterNot(_.startsWith("#"))
        .filter(_.nonEmpty)
        .toSeq

  val depGraph = loadDepenenecyGraph(
    scalaBinaryVersion,
    minStarsCount = minStarsCount,
    maxProjectsCount = Option(maxProjectsCount).filter(_ >= 0),
    requiredProjects = requiredProjects,
    filterPatterns = filterPatterns
  )
  val plan = makeDependenciesBasedBuildPlan(
    depGraph,
    replacedProjectsConfigPath,
    projectsConfigPath
  )

  import java.nio.file._
  val dataPath = Paths.get("data")
  val dest = dataPath.resolve("buildPlan.json")
  val json = toJson(plan)
  Files.createDirectories(dest.getParent)
  Files.write(dest, json.toString.getBytes)
