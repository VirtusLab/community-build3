import org.jsoup._
import collection.JavaConverters._
import java.nio.file._

def makeBuildPlan(depGraph: DependencyGraph): BuildPlan =
  val data = depGraph.projects
  val toLevelData = data.filter(_.p.stars > 20)
  val scalaSuffix = "_" + depGraph.scalaRelease // TODO - based this on version

  val fullInfo = data.map(l => l.p -> l).toMap
  case class ProjectVersion(p: Project, v: String)

  // TODO we assume that targets does not change between version, and this may not be true...
  val depsMap: Map[TargetId, ProjectVersion] = 
    data.flatMap(lp => lp.targets.map(t => t.id -> ProjectVersion(lp.p, lp.v))).toMap

  def flattenScalaDeps(p: LoadedProject): Seq[ProjectVersion] =
    p.targets.flatMap(_.deps.filter(_.id.name.endsWith(scalaSuffix))).distinct.flatMap(
      d => depsMap.get(d.id).map(_.copy(v = d.version))
    )

  val projectsDeps: Map[ProjectVersion, Seq[ProjectVersion]] = 
    toLevelData.map(lp => ProjectVersion(lp.p, lp.v) -> flattenScalaDeps(lp)).toMap

  val depScore = projectsDeps.flatMap(_._2).groupBy(identity)
    .map{ case (pv, c) => pv -> c.size}.toMap

  val topLevelPV = toLevelData.map(lp => ProjectVersion(lp.p, lp.v))
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

@main def printBuilPlan: BuildPlan = 
  val deps = loadDepenenecyGraph("3.0.0-RC1")
  val plan = makeBuildPlan(deps)
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

  Files.write(Paths.get("bp.txt"), bp.getBytes)
  plan