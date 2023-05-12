import org.jsoup._
import scala.jdk.CollectionConverters._
import java.nio.file._
import java.net.URL
import scala.sys.process._
import scala.util.CommandLineParser.FromString
import scala.util.Try

import scala.concurrent.*
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext
import java.util.concurrent.ForkJoinPool
import os.write
import scala.collection.mutable
import scala.collection.SortedMap
import os.CommandResult

class ConfigFiles(path: os.Path) {
  val projectsConfig: os.Path = path / "projects-config.conf"
  val filteredProjects: os.Path = path / "filtered-projects.txt"
  val replacedProjects: os.Path = path / "replaced-projects.txt"
  val slowProjects: os.Path = path / "slow-projects.txt"
  val customProjects: os.Path = path / "custom-projects.txt"
}

@main def storeDependenciesBasedBuildPlan(
    scalaBinaryVersion: String,
    minStarsCount: Int,
    maxProjectsCount: Int,
    requiredProjects: Seq[Project],
    configsPath: os.Path
) = {
  given confFiles: ConfigFiles = ConfigFiles(configsPath)
  // Most of the time is spend in IO, though we can use higher parallelism
  val threadPool = new ForkJoinPool(
    Runtime.getRuntime().availableProcessors() * 4
  )
  val customProjects = readNormalized(confFiles.customProjects).map(Project.load)
  given ExecutionContext = ExecutionContext.fromExecutor(threadPool)

  val task = for {
    dependencyGraph <- loadDepenenecyGraph(
      scalaBinaryVersion,
      minStarsCount = minStarsCount,
      maxProjectsCount = Option(maxProjectsCount).filter(_ >= 0),
      requiredProjects = requiredProjects,
      customProjects = customProjects,
      filterPatterns = loadFilters
    )
    _ = println(s"Loaded dependency graph: ${dependencyGraph.projects.size} projects")
    buildPlan <- makeDependenciesBasedBuildPlan(dependencyGraph)
    _ = println("Generated build plan")
  } yield {
    val configMap = SortedMap.from(buildPlan.map(p => p.project.coordinates -> p))
    val staged = splitIntoStages(buildPlan, loadLongBuildingProjects)
    val meta = BuildMeta(
      minStarsCount = minStarsCount,
      maxProjectsCount = maxProjectsCount,
      totalProjects = configMap.size
    )

    println("Projects in build plan: " + buildPlan.size)
    if sys.props.contains("opencb.coordinator.reproducer-mode")
    then {
      os.write.over(
        os.pwd / "data" / "buildPlan.json",
        toJson(staged),
        createFolders = true
      )
      println("Build plan saved")
    } else {
      os.write.over(
        workflowsDir / "buildConfig.json",
        toJson(configMap, pretty = true),
        createFolders = true
      )
      println("CI build config saved")
      os.write.over(
        workflowsDir / "buildPlan.yaml",
        createGithubActionJob(staged, meta)
      )
      println("CI build plan updated")
    }
  }
  try Await.result(task, 60.minute)
  catch {
    case ex: Throwable =>
      println(s"Uncought exception: $ex")
      ex.printStackTrace()
      threadPool.shutdownNow()
      threadPool.awaitTermination(10, SECONDS)
      throw ex
  }
}

val TagRef = """.+refs\/tags\/(.+)""".r

def findTag(repoUrl: String, version: String): Option[String] = {
  def retryConnect(retries: Int, backoffSeconds: Int = 1): CommandResult =
    val proc =
      os.proc("git", "ls-remote", "--tags", repoUrl)
        .call(check = false, stderr = os.Pipe)
    if (proc.exitCode == 0) proc
    else if retries > 0 && proc.err
        .lines()
        .exists(_.contains("Could not resolve host: github.com"))
    then
      Console.err.println(
        s"Github unavailable, retry with backoff ${backoffSeconds}s"
      )
      Thread.sleep(backoffSeconds)
      retryConnect(retries - 1, (backoffSeconds * 2) min 60)
    else
      Console.err.println(s"Failed to list tags of $repoUrl: ")
      proc.err.lines().foreach(Console.err.println)
      proc

  val lsRemote = retryConnect(10)
  if lsRemote.exitCode != 0
  then None
  else {
    val lines = lsRemote.out.lines().filter(_.contains(version)).toList
    val (exactMatch, partialMatch) = lines
      .partition(_.endsWith(version))
    (exactMatch ::: partialMatch) // sorted candidates
      .collectFirst { case TagRef(tag) => tag }.headOption
  }
}

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
  case _                               => false
}

// Needed as long as some projects use scala version prior to 3.0.0
def replaceScalaBinaryVersion(s: String) = s match {
  case WithExtractedScala3Suffix(prefix, _) => prefix + "_3"
}

def stripScala3Suffix(s: String) = s match {
  case WithExtractedScala3Suffix(prefix, _) => prefix
}

object DottyProject extends StarredProject("lampepfl", "dotty")(0)
def buildPlanCommons(depGraph: DependencyGraph) =
  val data = depGraph.projects
  val topLevelData = data

  val fullInfo = data.map(l => l.p -> l).toMap

  // TODO we assume that targets does not change between version, and this may not be true...
  val depsMap: Map[TargetId, ProjectVersion] =
    data
      .flatMap(lp => lp.targets.map(t => t.id -> ProjectVersion(lp.p, lp.v)))
      .toMap

  def flattenScalaDeps(p: LoadedProject): Seq[ProjectVersion] =
    p.targets
      .flatMap(_.deps.filter(dep => hasScala3Suffix(dep.id.name)))
      .distinct
      .flatMap(d => depsMap.get(d.id).map(_.copy(v = d.version)))

  val projectsDeps: Map[ProjectVersion, Seq[ProjectVersion]] =
    topLevelData
      .map(lp => ProjectVersion(lp.p, lp.v) -> flattenScalaDeps(lp))
      .toMap

  (topLevelData, fullInfo, projectsDeps)

def makeDependenciesBasedBuildPlan(depGraph: DependencyGraph)(using
    confFiles: ConfigFiles
): AsyncResponse[Array[ProjectBuildDef]] =
  val (topLevelData, fullInfo, projectsDeps) = buildPlanCommons(depGraph)
  val configDiscovery = ProjectConfigDiscovery(confFiles.projectsConfig.toIO)

  val dottyProjectName = "lampepfl_dotty"

  val replacementPattern = raw"(\S+)/(\S+) (\S+)/(\S+) ?(\S+)?".r
  val replacements =
    if !os.exists(confFiles.replacedProjects) || os.isDir(confFiles.replacedProjects)
    then Map.empty
    else
      readNormalized(confFiles.replacedProjects).map {
        case replacementPattern(org1, name1, org2, name2, branch) =>
          (org1, name1) -> ((org2, name2), Option(branch))
      }.toMap

  def projectRepoUrl(project: Project) =
    val originalCoords = (project.org, project.name)
    val (org, name) =
      replacements.get(originalCoords).map(_._1).getOrElse(originalCoords)
    s"https://github.com/${org}/${name}.git"

  def getRevision(project: Project) =
    val originalCoords = (project.org, project.name)
    replacements.get(originalCoords).map(_._2).flatten

  val projects = projectsDeps.keys.map(_.p).toList

  Future
    .traverse(projectsDeps.toList) { (project, deps) =>
      Future {
        val repoUrl = projectRepoUrl(project.p)
        val tag =
          getRevision(project.p).orElse(findTag(repoUrl, project.v))
        val self = project.p
        val dependencies = deps
          .map(_.p)
          .filter {
            case DottyProject => false
            case `self`       => false
            case dep          => projects.contains(dep)
          }
          .distinct
        ProjectBuildDef(
          project = project.p,
          dependencies = dependencies.toArray,
          repoUrl = repoUrl,
          revision = tag.getOrElse(""),
          version = project.v,
          targets = fullInfo(project.p).targets
            .map {
              case t @ Target.BuildAll => t.id.asMvnStr
              case t                   => stripScala3Suffix(t.id.asMvnStr)
            }
            .mkString(" "),
          config = configDiscovery(project, repoUrl, tag)
        )
      }
    }
    .map(_.filter(_.project != DottyProject).toArray)

private def loadFilters(using confFiles: ConfigFiles): Seq[String] = readNormalized(
  confFiles.filteredProjects
)
private def loadLongBuildingProjects(using confFiles: ConfigFiles): Seq[Project] =
  readNormalized(confFiles.slowProjects).flatMap {
    case s"$org/$repo" => Some(Project(org, repo))
    case malformed =>
      System.err.println(s"Malformed project long building project name: $malformed ")
      None
  }

def readNormalized(path: os.Path): Seq[String] =
  if !os.exists(path)
  then
    System.err.println(s"Not found file: $path")
    Nil
  else if os.isDir(path) then
    System.err.println(s"Unable to read directory: $path")
    Nil
  else
    os.read
      .lines(path)
      .map(_.trim())
      .filterNot(_.startsWith("#"))
      .filter(_.nonEmpty)
      .toSeq

type StagedBuildPlan = List[List[ProjectBuildDef]]
def splitIntoStages(
    projects: Array[ProjectBuildDef],
    longBuildingProjects: Seq[Project]
): StagedBuildPlan = {
  val deps = projects.map(v => (v.project, v)).toMap
  // GitHub Actions limits to 255 elements in matrix
  val maxStageSize = 255
  @scala.annotation.tailrec
  def groupByDeps(
      remaining: Set[ProjectBuildDef],
      done: Set[Project],
      acc: List[Set[ProjectBuildDef]]
  ): List[Set[ProjectBuildDef]] = {
    if remaining.isEmpty then acc.reverse
    else
      var (currentStage, newRemainings) = remaining.partition {
        _.dependencies.forall(done.contains)
      }
      if currentStage.isEmpty then {
        def hasCyclicDependencies(p: ProjectBuildDef) =
          p.dependencies.exists(deps(_).dependencies.contains(p.project))
        val cyclicDeps = newRemainings.filter(hasCyclicDependencies)
        if cyclicDeps.nonEmpty then {
          currentStage ++= cyclicDeps
          newRemainings --= cyclicDeps
          cyclicDeps.foreach(v =>
            println(
              s"Mitigated cyclic dependency in  ${v.project} -> ${v.dependencies.toList
                .filterNot(done.contains)}"
            )
          )
        } else {
          val (minDeps, tieBreakers) = newRemainings
            .groupBy(_.dependencies.count(!done.contains(_)))
            .toSeq
            .sortBy { (depsCount, _) => depsCount }
            .head
          def showCurrent = tieBreakers.map(_.project.coordinates).mkString(", ")
          System.err.println(
            s"Not found projects without already resolved dependencies, using [${tieBreakers.size}] projects with minimal dependency size=$minDeps : ${showCurrent}"
          )
          currentStage ++= tieBreakers
          newRemainings --= tieBreakers
        }
      }
      val names = currentStage.map(_.project)
      val currentStages = currentStage.grouped(maxStageSize).toList
      groupByDeps(newRemainings, done ++ names, currentStages ::: acc)
  }

  val (longRunningDefs, toSplit) = projects.toSet
    .partition(p => longBuildingProjects.contains(p.project))
  val longRunning = longRunningDefs.map(_.project)
  val staged = groupByDeps(toSplit, done = longRunning, Nil)

  val all = longRunningDefs :: staged
  all.map(_.toList.sortBy(_.project))
}

private given FromString[os.Path] = { str =>
  val nio = java.nio.file.Paths.get(str)
  os.Path(nio.toAbsolutePath())
}
private given FromString[Seq[Project]] = str =>
  str
    .split(",")
    .toSeq
    .filter(_.nonEmpty)
    .map(Project.load)

lazy val workflowsDir: os.Path = {
  val githubDir = os.rel / ".github"
  def loop(cwd: os.Path): os.Path = {
    val path = cwd / githubDir
    if os.exists(path) then path
    else loop(cwd / os.up)
  }
  loop(os.pwd) / "workflows"
}

case class BuildMeta(
    minStarsCount: Int,
    maxProjectsCount: Int,
    totalProjects: Int
)
def createGithubActionJob(
    plan: List[List[ProjectBuildDef]],
    meta: BuildMeta
): String = {
  class Printer() {
    private val buffer = mutable.StringBuilder()
    private var indentation = 0
    private def indent: String = "  " * indentation

    def println(line: String) =
      buffer.append(s"${indent}${line}${System.lineSeparator()}")
    def indented(block: => Unit) = {
      indentation += 1
      try block
      finally indentation -= 1
    }
    def mkString = buffer.mkString
  }
  type StageName = String
  def stageId(idx: Int): StageName = s"stage-$idx"
  val setupId = "setup-build"
  val setupOutputs = s"needs.$setupId.outputs"
  val printer = new Printer()
  import printer._
  println(s"""
    |# projects total:     ${meta.totalProjects}
    |# min stars count:    ${meta.minStarsCount}
    |# max projects count: ${meta.maxProjectsCount}
    |
    |name: "Execute Open Community Build plan"
    |on:
    |  workflow_call:
    |    inputs:
    |      published-scala-version:
    |        type: string
    |        description: 'Published Scala version to use, if empty new version of compiler would be build with default name based on the selected repository'
    |      repository-url:
    |        type: string
    |        description: "GitHub repository URL for compiler to build, ignored when published-scala-version is defined"
    |        default: "lampepfl/dotty"
    |      repository-branch:
    |        type: string
    |        description: "GitHub repository branch for compiler to build, ignored when published-scala-version is defined"
    |        default: "main"
    |      extra-scalac-options:
    |        type: string
    |        description: "List of scalacOptions which should be used when building projects. Multiple entires should be seperated by a single comma character `,`"
    |        default: ""
    |      disabled-scalac-options:
    |        type: string
    |        description: "List of scalacOptions which should be filtered out when building projects."
    |        default: ""
    |    outputs:
    |      used-scala-version:
    |        description: "Version of Scala used to run the build"
    |        value: $${{ jobs.setup-build.outputs.scala-version }}
    |    secrets:
    |      OPENCB_ELASTIC_USER:
    |        required: true
    |      OPENCB_ELASTIC_PSWD:
    |        required: true
    |
    |jobs:
    |  $setupId:
    |    runs-on: ubuntu-22.04
    |    continue-on-error: false
    |    outputs:
    |      scala-version:  $${{ steps.setup.outputs.scala-version }}
    |      maven-repo-url: $${{ steps.setup.outputs.maven-repo-url }}
    |    steps:
    |      - name: "Git Checkout"
    |        uses: actions/checkout@v3
    |      - name: "Setup build"
    |        uses: ./.github/actions/setup-build
    |        id: setup
    |        with:
    |          scala-version: $${{ inputs.published-scala-version }}
    |          repository-url: $${{ inputs.repository-url }}
    |          repository-branch: $${{ inputs.repository-branch }}
    |""".stripMargin)
  plan.filter(_.nonEmpty).zipWithIndex.foreach { case (projects, idx) =>
    // stage 0 reserved for long running jobs, no other step depends on it
    def hasExtendentBuildTime = idx == 0
    val prevStageId = Option.when(idx > 1) { stageId(idx - 1) }
    val needs = (setupId :: prevStageId.toList).mkString(", ")
    indented {
      println(s"${stageId(idx)}:")
      indented {
        println("runs-on: ubuntu-22.04")
        println(s"needs: [ $needs ]")
        println("continue-on-error: true")
        if hasExtendentBuildTime
        then println("timeout-minutes: 600")
        else println("timeout-minutes: 60")
        println("strategy:")
        indented {
          println("matrix:")
          indented {
            println("include:")
            for project <- projects
            do println(s"- name: \"${project.project.coordinates}\"")
          }
        }
        println("steps:")
        indented {
          println("- name: \"Git Checkout\"")
          println("  uses: actions/checkout@v3")
          println("- name: \"Build project\"")
          println("  uses: ./.github/actions/build-project")
          println("  with:")
          println("    project-name: ${{ matrix.name }}")
          println("    extra-scalac-options: ${{ inputs.extra-scalac-options }}")
          println("    disabled-scalac-options: ${{ inputs.disabled-scalac-options }}")
          println(s"    scala-version: $${{ $setupOutputs.scala-version }}")
          println(s"    maven-repo-url: $${{ $setupOutputs.maven-repo-url }}")
          println("    elastic-user: ${{ secrets.OPENCB_ELASTIC_USER }}")
          println("    elastic-password: ${{ secrets.OPENCB_ELASTIC_PSWD }}")
        }
      }
    }
  }
  printer.mkString
}
