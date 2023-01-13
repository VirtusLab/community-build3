import org.jsoup._
import collection.JavaConverters._
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

@main def storeDependenciesBasedBuildPlan(
    scalaBinaryVersion: String,
    minStarsCount: Int,
    maxProjectsCount: Int,
    requiredProjects: Seq[Project],
    replacedProjectsConfigPath: os.Path,
    projectsConfigPath: os.Path,
    projectsFilterPath: os.Path
) = {
  // Most of the time is spend in IO, though we can use higher parallelism
  val threadPool = new ForkJoinPool(
    Runtime.getRuntime().availableProcessors() * 4
  )
  given ExecutionContext = ExecutionContext.fromExecutor(threadPool)

  val task = for {
    dependencyGraph <- loadDepenenecyGraph(
      scalaBinaryVersion,
      minStarsCount = minStarsCount,
      maxProjectsCount = Option(maxProjectsCount).filter(_ >= 0),
      requiredProjects = requiredProjects,
      filterPatterns = loadFilters(projectsFilterPath)
    )
    buildPlan <- makeDependenciesBasedBuildPlan(
      dependencyGraph,
      replacedProjectsConfigPath,
      projectsConfigPath
    )
  } yield {
    val configMap = SortedMap.from(buildPlan.map(p => p.name -> p))
    val staged = splitIntoStages(buildPlan)
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
  try Await.result(task, 30.minute)
  catch {
    case ex: Throwable =>
      println(s"Uncought exception: $ex")
      ex.printStackTrace()
      threadPool.shutdownNow()
      threadPool.awaitTermination(10, SECONDS)
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

def makeStepsBasedBuildPlan(depGraph: DependencyGraph): BuildPlan =
  val (topLevelData, fullInfo, projectsDeps) = buildPlanCommons(depGraph)

  val depScore =
    projectsDeps
      .flatMap(_._2)
      .groupBy(identity)
      .map { case (pv, c) => pv -> c.size }
      .toMap

  val topLevelPV = topLevelData.map(lp => ProjectVersion(lp.p, lp.v))
  val allPVs = (topLevelPV ++ depScore.keys).distinct

  def majorMinor(v: String) = v.split('.').take(2).mkString(".")
  def patch(pv: ProjectVersion) = pv.v.split('.').drop(2).mkString(".")
  val overrides = allPVs
    .groupBy(pv => (pv.p, majorMinor(pv.v)))
    .flatMap { case ((p, mm), pvs) =>
      val oVersion =
        if pvs.size == 1 then pvs.head.v
        else
          val v =
            pvs
              .maxBy(patch)
              .v // TODO make sure that we do built requested version!
          println(s"Forcing version for $p to $v from: " + pvs.map(_.v))
          v
      pvs.map(_ -> ProjectVersion(p, oVersion))
    }
    .toMap

  case class ToBuild(pv: ProjectVersion, deps: Seq[ProjectVersion]):
    def resolve(compiled: Set[ProjectVersion]) =
      copy(deps = deps.filterNot(compiled))

  val allToBuild = overrides.values.toSeq.distinct
    .sortBy(_.p.stars)
    .reverse
    .map(pv =>
      ToBuild(
        pv,
        projectsDeps.getOrElse(pv, Nil).filter(_.p != pv.p).map(overrides)
      )
    )

  val (scala3, rawToBuild) =
    allToBuild.partition(_.pv.p == Project("lampepfl", "dotty")(0))

  val scala3set = scala3.map(_.pv).toSet
  val toBuilds = rawToBuild.map(_.resolve(scala3set))

  println(
    s"Will build: (${topLevelPV.size} original and ${toBuilds.size} total)"
  )

  @annotation.tailrec
  def step(
      built: Seq[Seq[ProjectVersion]],
      toComplete: Seq[ToBuild]
  ): Seq[Seq[ProjectVersion]] =
    if toComplete.isEmpty then built
    else
      val (completed, rawTodo) = toComplete.partition(_.deps.isEmpty)
      val (actualCompleted, todo) =
        if completed.nonEmpty then (completed.map(_.pv), rawTodo)
        else
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

  def isScala(dep: Dep) =
    dep.id.org == "org.scala-lang" // TODO should be smarter!

  val computedSteps =
    builtSteps.reverse.foldLeft(init) { case ((steps, overrides), pvs) =>
      def buildStep(pv: ProjectVersion): BuildStep =
        // This assumes that we've got the same targets across different versions
        val targets = fullInfo(pv.p).targets
        val allOverrides =
          targets
            .flatMap(_.deps)
            .distinct
            .filterNot(isScala)
            .flatMap(overrides.get)
        val publishVersion = depScore.get(pv).map(_ => pv.v + "-communityBuild")
        BuildStep(pv.p, pv.v, publishVersion, targets.map(_.id), allOverrides)

      val newSteps = pvs.sortBy(-_.p.stars).map(buildStep)
      val newOverrides =
        for
          step <- newSteps
          newVersion <- step.publishVersion.toSeq
          tid <- step.targets
        yield Dep(tid, step.originalVersion) -> Dep(tid, newVersion)

      (steps :+ newSteps, overrides ++ newOverrides)
    }

  BuildPlan(depGraph.scalaRelease, computedSteps._1)

def makeDependenciesBasedBuildPlan(
    depGraph: DependencyGraph,
    replacedProjectsConfigPath: os.Path,
    internalProjectConfigsPath: os.Path
): AsyncResponse[Array[ProjectBuildDef]] =
  val (topLevelData, fullInfo, projectsDeps) = buildPlanCommons(depGraph)
  val configDiscovery = ProjectConfigDiscovery(internalProjectConfigsPath.toIO)

  val dottyProjectName = "lampepfl_dotty"

  val replacementPattern = raw"(\S+)/(\S+) (\S+)/(\S+) ?(\S+)?".r
  val replacements =
    if !os.exists(replacedProjectsConfigPath) ||
      os.isDir(replacedProjectsConfigPath)
    then Map.empty
    else
      os.read
        .lines(replacedProjectsConfigPath)
        .map(_.trim)
        .filter(line => line.nonEmpty && !line.startsWith("#"))
        .map { case replacementPattern(org1, name1, org2, name2, branch) =>
          (org1, name1) -> ((org2, name2), Option(branch))
        }
        .toMap

  def projectRepoUrl(project: Project) =
    val originalCoords = (project.org, project.name)
    val (org, name) =
      replacements.get(originalCoords).map(_._1).getOrElse(originalCoords)
    s"https://github.com/${org}/${name}.git"

  def getRevision(project: Project) =
    val originalCoords = (project.org, project.name)
    replacements.get(originalCoords).map(_._2).flatten

  val projectNames = projectsDeps.keys.map(_.showName).toList

  Future
    .traverse(projectsDeps.toList) { (project, deps) =>
      Future {
        val repoUrl = projectRepoUrl(project.p)
        val tag =
          getRevision(project.p).orElse(findTag(repoUrl, project.v))
        val name = project.showName
        val dependencies = deps
          .map(_.showName)
          .filter(depName =>
            projectNames.contains(
              depName
            ) && depName != name && depName != dottyProjectName
          )
          .distinct
        ProjectBuildDef(
          name = name,
          dependencies = dependencies.toArray,
          repoUrl = repoUrl,
          revision = tag.getOrElse(""),
          version = project.v,
          targets = fullInfo(project.p).targets
            .map(t => stripScala3Suffix(t.id.asMvnStr))
            .mkString(" "),
          config = configDiscovery(project, repoUrl, tag)
        )
      }
    }
    .map(_.filter(_.name != dottyProjectName).toArray)

private def loadFilters(projectsFilterPath: os.Path): Seq[String] =
  if !os.exists(projectsFilterPath) || os.isDir(projectsFilterPath) then Nil
  else
    os.read
      .lines(projectsFilterPath)
      .map(_.trim())
      .filterNot(_.startsWith("#"))
      .filter(_.nonEmpty)
      .toSeq

type StagedBuildPlan = List[List[ProjectBuildDef]]
def splitIntoStages(projects: Array[ProjectBuildDef]): StagedBuildPlan = {
  val deps = projects.map(v => (v.name, v)).toMap
  val maxStageSize = 255 // due to GitHub actions limit
  @scala.annotation.tailrec
  def groupByDeps(
      remaining: Set[ProjectBuildDef],
      done: Set[String],
      acc: List[Set[ProjectBuildDef]]
  ): List[Set[ProjectBuildDef]] = {
    if remaining.isEmpty then acc.reverse
    else
      var (currentStage, newRemainings) = remaining.partition {
        _.dependencies.forall(done.contains)
      }
      if currentStage.isEmpty then {
        def hasCyclicDependencies(p: ProjectBuildDef) =
          p.dependencies.exists(deps(_).dependencies.contains(p.name))
        val cyclicDeps = newRemainings.filter(hasCyclicDependencies)
        currentStage ++= cyclicDeps
        newRemainings --= cyclicDeps

        cyclicDeps.foreach(v =>
          println(
            s"Mitigated cyclic dependency in  ${v.name} -> ${v.dependencies.toList
              .filterNot(done.contains)}"
          )
        )
      }
      val names = currentStage.map(_.name)
      val currentStages = currentStage.grouped(maxStageSize).toList
      groupByDeps(newRemainings, done ++ names, currentStages ::: acc)
  }

  groupByDeps(projects.toSet, Set.empty, Nil)
    .map(_.toList.sortBy(_.name))
}

private given FromString[os.Path] = { str =>
  val nio = java.nio.file.Paths.get(str)
  os.Path(nio.toAbsolutePath())
}
private given FromString[Seq[Project]] = str =>
  str.split(",").toSeq.filter(_.nonEmpty).map {
    case s"${org}/${name}" => Project(org, name)(Int.MaxValue)
    case _                 => throw new IllegalArgumentException
  }

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
    |name: "Open Community Build"
    |on:
    |  workflow_call:
    |    inputs:
    |      published-scala-version:
    |        type: string
    |        description: 'Published Scala version to use'
    |        required: true
    |  workflow_dispatch:
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
  plan.zipWithIndex.foreach { case (projects, idx) =>
    indented {
      println(s"${stageId(idx)}:")
      indented {
        println("runs-on: ubuntu-22.04")
        println(s"needs: ${if idx == 0 then setupId else stageId(idx - 1)}")
        println("continue-on-error: true")
        println("strategy:")
        indented {
          println("matrix:")
          indented {
            println("include:")
            for project <- projects
            do println(s"- name: ${project.name}")
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
          println("    extra-scalac-options: $${{ inputs.extra-scalac-options }}")
          println(s"    scala-version: $${{ $setupOutputs.scala-version }}")
          println(s"    maven-repo-url: $${{ $setupOutputs.maven-repo-url }}")
          println("    elastic-user: ${{ secrets.OPENCB_ELASTIC_USER }}")
          println("    elastic-password: ${{ secrets.OPENCB_ELASTIC_PSWD }}")
        }
      }
    }
  }
  println(s"""
  |  create-raport:
  |    needs: [${stageId(plan.indices.last)}]
  |    runs-on: ubuntu-22.04
  |    continue-on-error: true
  |    steps:
  |      - name: Git Checkout
  |        uses: actions/checkout@v3
  |      - name: Install coursier
  |        uses: coursier/setup-action@v1
  |        with:
  |          apps: scala-cli
  |      
  |      - name: Generate raport
  |        env: 
  |          ES_USER: $${{ secrets.OPENCB_ELASTIC_USER }}
  |          ES_PASSWORD: $${{ secrets.OPENCB_ELASTIC_PSWD }}
  |        run: | 
  |          scalaVersion=$${{ $setupOutputs.scala-version }}
  |          lastRC="$$(./scripts/lastVersionRC.sc)"
  |          lastStable=$$(./scripts/lastVersionStable.sc)
  |
  |          ./scripts/raport-regressions.scala $$scalaVersion > raport-full.md
  |          ./scripts/raport-regressions.scala $$scalaVersion --compare-with $$lastRC > raport-compare-$$lastRC.md
  |          ./scripts/raport-regressions.scala $$scalaVersion --compare-with $$lastRC > raport-compare-$$lastStable.md
  |
  |      - name: Upload raports
  |        uses: actions/upload-artifact@v3
  |        with:
  |          name: build-raports
  |          path: $${{ github.workspace }}/raport-*.md
  |""".stripMargin)

  printer.mkString
}
