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

val TagRef = """.+refs\/tags\/(.+)""".r

def findTag(repoUrl: String, version: String): Either[String, String] =
  val cmd = Seq("git", "ls-remote", "--tags", repoUrl)
  Try {
    val lines = cmd.!!.linesIterator.filter(_.contains(version)).toList
    val (exactMatch, partialMatch) = lines.partition(_.endsWith(version))
    (exactMatch ::: partialMatch).collectFirst { case TagRef(tag) => tag } match
      case Some(tag) => Right(tag)
      case _ => Left(s"No tag in:\n${lines.map("-" + _ + "_").mkString("\n")}")
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

@main def printBuildPlan: BuildPlan =
  given ExecutionContext = scala.concurrent.ExecutionContext.global
  val result = for
    deps <- loadDepenenecyGraph("3", minStarsCount = 100)
    plan = makeStepsBasedBuildPlan(deps)
  yield {
    val niceSteps = plan.steps.zipWithIndex.map { case (steps, nr) =>
      val items =
        def versionMap(step: BuildStep) =
          step.originalVersion + step.publishVersion.fold("")(" -> " + _)
        def overrides(step: BuildStep) = step.depOverrides match
          case Nil  => ""
          case deps => "\n    with " + deps.map(_.asMvnStr).mkString(", ")

        steps.map(step =>
          "  " + step.p.org + "/" + step.p.name + " @ " + versionMap(
            step
          ) + overrides(step)
        )
      items.mkString(s"Step ${nr + 1}:\n", "\n", "\n")
    }

    val bp = "Buildplan:\n" + niceSteps.mkString("\n")

    Files.write(Paths.get("data", "bp.txt"), bp.getBytes)
    plan
  }
  Await.result(result, ???)

def makeDependenciesBasedBuildPlan(
    depGraph: DependencyGraph,
    replacedProjectsConfigPath: String,
    internalProjectConfigsPath: String
): AsyncResponse[Array[ProjectBuildDef]] =
  val (topLevelData, fullInfo, projectsDeps) = buildPlanCommons(depGraph)
  val configDiscovery = ProjectConfigDiscovery(
    java.io.File(internalProjectConfigsPath)
  )

  val dottyProjectName = "lampepfl_dotty"

  val replacementPattern = raw"(\S+)/(\S+) (\S+)/(\S+) ?(\S+)?".r
  val replacements =
    if (!Paths.get(replacedProjectsConfigPath).toFile.exists) Map.empty
    else
      scala.io.Source
        .fromFile(replacedProjectsConfigPath)
        .getLines
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
          getRevision(project.p).orElse(findTag(repoUrl, project.v).toOption)
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

private given FromString[Seq[Project]] = str =>
  str.split(",").toSeq.filter(_.nonEmpty).map {
    case s"${org}/${name}" => Project(org, name)(Int.MaxValue)
    case _                 => throw new IllegalArgumentException
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
  val threadPool = new ForkJoinPool()
  given ExecutionContext = ExecutionContext.fromExecutor(threadPool)
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

  val task = for {
    depGraph <- loadDepenenecyGraph(
      scalaBinaryVersion,
      minStarsCount = minStarsCount,
      maxProjectsCount = Option(maxProjectsCount).filter(_ >= 0),
      requiredProjects = requiredProjects,
      filterPatterns = filterPatterns
    )
    plan <- makeDependenciesBasedBuildPlan(
      depGraph,
      replacedProjectsConfigPath,
      projectsConfigPath
    )
  } yield {
    val planStages: List[List[ProjectBuildDef]] = {
      @scala.annotation.tailrec
      def groupByDeps(
          remaining: Set[ProjectBuildDef],
          done: Set[String],
          acc: List[Set[ProjectBuildDef]]
      ): List[Set[ProjectBuildDef]] =
        if remaining.isEmpty then acc.reverse
        else
          var (currentStage, newRemainings) = remaining.partition {
            _.dependencies.forall(done.contains)
          }
          if currentStage.isEmpty then {
            val deps = plan.map(v => (v.name, v)).toMap
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
          groupByDeps(newRemainings, done ++ names, currentStage :: acc)
      end groupByDeps
      groupByDeps(plan.toSet, Set.empty, Nil)
        .map(_.toList.sortBy(_.name))
    }

    planStages.zipWithIndex.foreach { (group, idx) =>
      println(s"Stage $idx: ${group.size} projects: ${group.map(_.name)}")
    }

    import java.nio.file._
    val dataPath = Paths.get("data")
    val dest = dataPath.resolve("buildPlan.json")
    println("Projects in build plan: " + plan.size)
    val json = toJson(planStages)
    Files.createDirectories(dest.getParent)
    Files.write(dest, json.toString.getBytes)
  }
  try Await.ready(task, 15.minute)
  catch {
    case ex: Throwable =>
      println(s"Exception $ex")
      threadPool.shutdownNow()
      threadPool.awaitTermination(1, MINUTES)

  }
