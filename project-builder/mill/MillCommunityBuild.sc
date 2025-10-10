// Injects for Mill 0.x
package build
import $ivy.`com.lihaoyi::upickle:3.0.0`
import MillVersionCompat.compat.{
  CoursierModule,
  JavaModule,
  PublishModule,
  ScalaModule,
  TestModule,
  TestResult,
  ZincWorkerModule,
  Task,
  Val,
  toZincWorker,
  ZincWorkerOverrideForScala3_8
}
import CommunityBuildCore.Scala3CommunityBuild.{
  TestingMode => _,
  ProjectBuildConfig => _,
  ProjectOverrides => _,
  _
}
import scala.reflect.ClassTag
import CommunityBuildCore.Scala3CommunityBuild.Utils._
// Make sure that following classes are in sync with the ones defined in CommunityBuildcore,
//  upickle has problems with classess imported from other file when creating readers
case class ProjectBuildConfig(
    projects: ProjectsConfig = ProjectsConfig(),
    tests: TestingMode = TestingMode.Full
)

case class ProjectOverrides(tests: Option[TestingMode] = None)
case class ProjectsConfig(
    exclude: List[String] = Nil,
    overrides: Map[String, ProjectOverrides] = Map.empty
)

sealed trait TestingMode
object TestingMode {
  case object Disabled extends TestingMode
  case object CompileOnly extends TestingMode
  case object Full extends TestingMode
}
// End of overrides

import TaskEvaluator.EvalResult
import serialization.OptionPickler.read

import scala.reflect.ClassTag
import mill.{api, T, Module, Agg}
import mill.scalalib.api.CompilationResult
import mill.eval._
import mill.define.{Cross => DefCross, NamedTask, Segment}
import requests._
import coursier.maven.MavenRepository
import coursier.Repository

@scala.annotation.nowarn
trait CommunityBuildCoursierModule extends CoursierModule with JavaModule {
  private val mavenRepoUrl: Option[String] = sys.props
    .get("communitybuild.maven.url")
    .map(_.stripSuffix("/"))
  private val mavenRepo = mavenRepoUrl.map(MavenRepository(_))

  override def repositoriesTask: Task[Seq[Repository]] = T.task {
    mavenRepo.foldLeft(super.repositoriesTask())(_ :+ _)
  }
  // Override zinc worker, we need to set custom repostitories there are well,
  // to allow to use our custom repo
  override def zincWorker = toZincWorker(CommunityBuildZincWorker)
  private object CommunityBuildZincWorker extends ZincWorkerModule with CoursierModule with ZincWorkerOverrideForScala3_8 {
    override def repositoriesTask = T.task {
      mavenRepo.foldLeft(super.repositoriesTask())(_ :+ _)
    }
  }
}

// Extension to publish module allowing to upload artifacts to custom maven repo
// Left for compliance with legacy versions
trait CommunityBuildPublishModule extends PublishModule with CommunityBuildCoursierModule

/** Replace all Scala in crossVersion with `buildScalaVersion` if its matching `buildScalaVersion`
  * binary version
  * @param `crossVersions`
  *   sequence of cross versions, in the form of either `String` or `Product` of Strings (based on
  *   the mill api)
  */
def mapCrossVersions[T](
    buildScalaVersion: String,
    crossVersions: T*
)(implicit dummy: DummyImplicit): Seq[T] = mapCrossVersions(buildScalaVersion, crossVersions.toSeq)

// Variant of map cross versions used by legacy Mill versions <= 0.10.x
def mapCrossVersionsAny(
    buildScalaVersion: String,
    crossVersions: Seq[Any]
): Seq[Any] = mapCrossVersions(buildScalaVersion, crossVersions)

private lazy val originalCrossScalaVersions = scala.collection.mutable.Set.empty[String] ++ sys.env.get("OVERRIDEN_SCALA_VERSION").filterNot(_.isEmpty())

def mapCrossVersions[T](
    buildScalaVersion: String,
    crossVersions: Seq[T]
): Seq[T] = {
  implicit val ctx = ExtractorsCtx(buildScalaVersion)
  // Map products to lists (List[_] <: Product ) for stable pattern matching
  val resultCrossVersions = for {
    crossEntry <- crossVersions
    cross = crossEntry match {
      case product: Product => product.productIterator.toList
      case other            => other
    }
  // Register original Scala versions
   replacedVersion = cross match {
    case v @ IsScalaVersion()                     => Some(v)
    case List(v @ IsScalaVersion(), crossVersion) => Some(v)
    case List(crossVersion, v @ IsScalaVersion()) => Some(v)
    case _ => None
   }
   _ = replacedVersion.filter(_ != buildScalaVersion).foreach(originalCrossScalaVersions += _)
   // Map scala versions matching specified Scal aversion
   mappedCross = cross match {
      case MatchesScalaBinaryVersion()                     => buildScalaVersion
      case List(MatchesScalaBinaryVersion(), crossVersion) => (buildScalaVersion, crossVersion)
      case List(crossVersion, MatchesScalaBinaryVersion()) => (crossVersion, buildScalaVersion)
      case _                                               => crossEntry
    }
    version <- Seq(mappedCross, crossEntry).distinct
  } yield {
    if (version != crossEntry) {
      logOnce(s"Use cross-version $mappedCross instead of $crossEntry")
    }
    version.asInstanceOf[T]
  }
  resultCrossVersions.distinct
}

private object ScalacOptionsSettings {
  private def parse(propName: String): List[String] = 
    sys.props
      .get(propName)
      .map(_.split(',').filter(_.nonEmpty).toList)
      .getOrElse(Nil)
  val append = parse("communitybuild.appendScalacOptions")
  val remove = parse("communitybuild.removeScalacOptions")
}

def mapScalacOptions(scalaVersion: String, current: Seq[String]): Seq[String] = 
  if(scalaVersion.startsWith("3.")) CommunityBuildCore.Scala3CommunityBuild.Utils.mapScalacOptions(
    scalaVersion = Some(scalaVersion),
    current = current,
    append = ScalacOptionsSettings.append,
    remove = ScalacOptionsSettings.remove
  ) else current

case class ModuleInfo(org: String, name: String, module: Module) {
  val targetName = s"$org%$name"
}
case class Ctx(
    root: mill.define.Module,
    scalaVersion: String,
    evaluator: Evaluator,
    log: mill.api.Logger
) {
  lazy val cross = Segment.Cross(scalaVersion :: Nil)
}

@scala.annotation.nowarn
class MillTaskEvaluator()(implicit ctx: Ctx) extends TaskEvaluator[NamedTask] {
  import TaskEvaluator._
  def mayRetry[T](task: NamedTask[T])(evaluate: NamedTask[T] => EvalResult[T]): EvalResult[T] = evaluate(task) match {
      case EvalResult.Failure(reasons, _) if reasons.exists {
          case ex: AssertionError => ex.getMessage.contains("overlapping patches")
          case _ => false
        } =>  evaluate(task)
      case result => result
    }
  def eval[T](task: NamedTask[T]): EvalResult[T] = {
    val evalStart = System.currentTimeMillis()
    val result = tryEval(task)
    val tookMillis = (System.currentTimeMillis() - evalStart).toInt
    result match {
      case api.Result.Success(v) =>
        ctx.log.info(s"Successfully evaluated $task")
        EvalResult.Value(v, evalTime = tookMillis)
      case failure: api.Result.Failing[_] =>
        ctx.log.error(s"Failed to evaluated $task: ${failure}")
        val reason = failure match {
          case api.Result.Exception(throwable, _) => throwable
          case api.Result.Failure(msg, value) =>
            EvaluationFailure(msg + value.fold("")(" - with value: " + _))
        }
        EvalResult.Failure(reason :: Nil, evalTime = tookMillis)
      case other =>
        EvalResult.Failure(List(EvaluationFailure(other.toString())), evalTime = tookMillis)
    }
  }

  def evalAsDependencyOf[T](optTask: Option[NamedTask[T]])(
      dependencies: EvalResult[_]*
  ): EvalResult[T] = {
    optTask.fold[EvalResult[T]](EvalResult.Skipped)(evalAsDependencyOf(dependencies: _*)(_))
  }
}

// Main entry point for Mill community build
// Evaluate tasks until first failure and publish report
def runBuild(configJson: String, projectDir: String, targets: Seq[String])(implicit ctx: Ctx) = {
  val outputDir: os.Path = os.Path.expandUser(projectDir) / os.up
  println(s"Build config: ${configJson}")
  val config = read[ProjectBuildConfig](configJson)
  println(s"Parsed config: ${config}")
  val filteredTargets = filterTargets(targets, config.projects.exclude.map(_.r))
  val mappings = checkedModuleMappings(filteredTargets.toSet)
  val topLevelModules =
    if (targets.contains("*%*"))
      mappings.map(_._2).toSet.filter(_.org.nonEmpty) // exclude non-published modules
    else
      mappings.collect {
        case (target, info) if filteredTargets.contains(target) => info
      }.toSet
  val moduleDeps: Map[Module, Seq[ModuleInfo]] =
    ctx.root.millInternal.modules.collect { case module: PublishModule =>
      val mapped = for {
        module <- module.moduleDeps
        api.Result.Success((_, mapped)) <- toMappingInfo(module).asSuccess
      } yield mapped
      module -> mapped
    }.toMap

  @annotation.tailrec
  def flatten(soFar: Set[ModuleInfo], toCheck: Set[ModuleInfo]): Set[ModuleInfo] =
    toCheck match {
      case e if e.isEmpty => soFar
      case mDeps =>
        val deps = moduleDeps(mDeps.head.module).filterNot(soFar.contains)
        flatten(soFar ++ deps, mDeps.tail ++ deps)
    }

  val projectsToTest = flatten(topLevelModules, topLevelModules)
  val projectsBuildResults = for {
    (ModuleInfo(org, name, module: ScalaModule), idx) <- projectsToTest.toList.zipWithIndex
  } yield {
    ctx.log.info(s"\nStarting build for $name - [$idx/${projectsToTest.size}]")
    val evaluator = new MillTaskEvaluator()
    import evaluator._
    val overrides = {
      val overrides = config.projects.overrides
      overrides
        .get(name)
        .orElse {
          overrides.collectFirst {
            // No Regex.matches in Scala 2.12
            // Exclude cases when excluded name is a prefix of other project
            case (key, value)
                if key.r.findFirstIn(name).isDefined &&
                  !name.startsWith(key) =>
              value
          }
        }
    }
    val testingMode = overrides.flatMap(_.tests).getOrElse(config.tests)

    val testModule = module.millInternal.modules.toList
      .collect { case module: TestModule => module } match {
      case Nil =>
        ctx.log.info(s"No test module defined in $module")
        None
      case single :: Nil => Some(single)
      case multiple @ (first :: _) =>
        ctx.log.info(s"Multiple test modules defined in $module, using $first")
        Some(first)
    }
    def test[T](selector: TestModule => NamedTask[T]): Option[NamedTask[T]] =
      testModule.map(selector)

    val compileResult = mayRetry(module.compile)(eval)
    val docResult = mayRetry(module.docJar){
      evalAsDependencyOf(compileResult)
    }
    val testsCompileResult =
      test(_.compile).fold[EvalResult[CompilationResult]](EvalResult.skipped) {
        mayRetry(_){
          evalWhen(testingMode != TestingMode.Disabled, compileResult)
        }
      }
    val testsExecuteResults =
      test(_.test()).fold[EvalResult[Seq[TestResult]]](EvalResult.skipped) {
        evalWhen(testingMode == TestingMode.Full, testsCompileResult)(_).map(_._2)
      }
    val publishResult = module match {
      case module: CommunityBuildPublishModule =>
        PublishResult(
          evalAsDependencyOf(compileResult, docResult)(
            module.publishLocal( /*localIvyRepo=*/ null /* use default */ )
          )
        )
  
      case _ =>
        ctx.log.error(s"Module $module is not a publish module, skipping publishing")
        PublishResult(Status.Skipped, tookMs = 0)
    }

    ModuleBuildResults(
      artifactName = name,
      compile = collectCompileResults(compileResult),
      doc = DocsResult(docResult.map(_.path.toIO)),
      testsCompile = collectCompileResults(testsCompileResult),
      testsExecute = collectTestResults(testsExecuteResults),
      publish = publishResult,
      metadata = ModuleMetadata(
        crossScalaVersions = originalCrossScalaVersions.toSeq // Not project-specific
      )
    )
  }

  val buildSummary = BuildSummary(projectsBuildResults)

  ctx.log.info(s"""
    |************************
    |Build summary:
    |${buildSummary.toJson}
    |************************"
    |""".stripMargin)

  os.write.over(outputDir / "build-summary.txt", buildSummary.toJson)

  val failedModules = projectsBuildResults
    .filter(_.hasFailedStep)
    .map(_.artifactName)
  val hasFailedSteps = failedModules.nonEmpty
  val buildStatus =
    if (hasFailedSteps) "failure"
    else "success"
  os.write.over(outputDir / "build-status.txt", buildStatus)
  if (hasFailedSteps) {
    throw new ProjectBuildFailureException(failedModules)
  }
}

private def collectCompileResults(evalResult: EvalResult[CompilationResult]): CompileResult = {
  // TODO: No direct access to CompileAnalysis, CompileResult contains path to serialized analysis
  // However using it would require external dependencies
  CompileResult(
    evalResult.toStatus,
    failureContext = evalResult.toBuildError,
    warnings = 0,
    errors = 0,
    tookMs = evalResult.evalTime
  )
}

private def collectTestResults(evalResult: EvalResult[Seq[TestResult]]): TestsResult = {
  import sbt.testing.Status
  val default = TestsResult(
    status = evalResult.toStatus,
    overall = TestStats.empty,
    tookMs = evalResult.evalTime
  )
  evalResult match {
    case EvalResult.Value(results, tookMs) =>
      // status is a string defined as sbt.testing.Status.toString
      val resultsStatus = results
        .groupBy(_.status)
        .map { case (key, values) => sbt.testing.Status.valueOf(key) -> values.size }

      def countOf(selected: Status*) = selected.foldLeft(0)(_ + resultsStatus.getOrElse(_, 0))

      default.copy(
        overall = TestStats(
          passed = countOf(Status.Success),
          failed = countOf(Status.Error, Status.Failure, Status.Canceled),
          ignored = countOf(Status.Ignored),
          skipped = countOf(Status.Skipped)
        )
      )
    case _ => default.copy(failureContext = evalResult.toBuildError)
  }
}

private def toMappingInfo(
    module: JavaModule
)(implicit ctx: Ctx): api.Result[(String, ModuleInfo)] = {
  for {
    org <- module match {
      case m: PublishModule => tryEval(m.pomSettings).map(_.organization)
      case _                =>
        // it's not a published module, we don't need to care about it as it's not going to be enlisted in targets
        api.Result.Success("")
    }
    name <- tryEval(module.artifactName)
    info = ModuleInfo(org, name, module)
  } yield info.targetName -> info
}

type ModuleMappings = Map[String, ModuleInfo]
def moduleMappings(implicit ctx: Ctx): ModuleMappings = {
  import mill.api.Result.{Success => S}
  ctx.root.millInternal.modules.flatMap {
    case module: ScalaModule =>
      // Result does not have withFilter method, wrap it into the Option to allow for-comprehension
      for {
        S(scalaVersion) <- tryEval(module.scalaVersion).asSuccess
        if scalaVersion == ctx.scalaVersion

        S(platformSuffix: String) <- tryEval(module.platformSuffix).asSuccess
        if platformSuffix.isEmpty // is JVM

        S(mapping @ (targetName, ModuleInfo(_, _, module))) <- toMappingInfo(module).asSuccess
      } yield mapping
    case other =>
      // We're only intrested in Scala modules
      None
  }.toMap
}

private def checkedModuleMappings(
    targetStrings: Set[String]
)(implicit ctx: Ctx): ModuleMappings = {
  val mappings = moduleMappings(ctx)
  val unmatched = targetStrings.diff(mappings.keySet).diff(Set("*%*"))
  if (unmatched.nonEmpty) {
    val msg = s"Failed to resolve mappings for ${unmatched.size}/${targetStrings.size} targets: ${unmatched.mkString(", ")}\n" + s"Found targets [${mappings.keySet.size}]: ${mappings.keySet.toSeq.sorted.mkString(", ")}"
    if(unmatched.size == targetStrings.size) sys.error(msg)
    else System.err.println(msg)
  }
  mappings
}

// Evaluation of mill targets/commands
private def tryEval[T](task: NamedTask[T])(implicit ctx: Ctx): api.Result[T] = {
  val evalState = ctx.evaluator.evaluate(Agg(task))
  val failure = evalState.failing.values().flatten.toSeq.headOption
  def result = evalState.rawValues.head
  failure.getOrElse(result).map {
    case Val(value) => value.asInstanceOf[T]
    case value      => value.asInstanceOf[T]
  }
}

// Extractors
private case class ExtractorsCtx(scalaVersion: String) {
  val SymVer(scalaMajor, scalaMinor, _, _) = scalaVersion
  val currentBinaryVersion =
    if (scalaMajor == 2) s"$scalaMajor.$scalaMinor" else scalaMajor
}
private val SymVer = raw"(\d+)\.(\d+)\.(\d+)(\-.*)?".r
private object IsScalaVersion {
  def unapply(v: String): Boolean = v match {
    case SymVer("3", _, _, _)                  => true
    case SymVer("2", "11" | "12" | "13", _, _) => true
    case _                                     => false
  }
}
private object MatchesScalaBinaryVersion {
  def unapply(v: String)(implicit ctx: ExtractorsCtx): Boolean = v match {
    case IsScalaVersion() => v.toString.startsWith(ctx.currentBinaryVersion)
    case _                => false
  }
}

object serialization {
  object OptionPickler extends upickle.AttributeTagged {
    override implicit def OptionWriter[T: Writer]: Writer[Option[T]] =
      implicitly[Writer[T]].comap[Option[T]] {
        case None    => null.asInstanceOf[T]
        case Some(x) => x
      }

    override implicit def OptionReader[T: Reader]: Reader[Option[T]] = {
      new Reader.Delegate[Any, Option[T]](implicitly[Reader[T]].map(Some(_))) {
        override def visitNull(index: Int) = None
      }
    }
  }

  // Used to asssume coding of Option as nulls, instead of arrays (default)
  import OptionPickler._

  implicit lazy val TestingModeRW: ReadWriter[TestingMode] = {
    import TestingMode._
    val DisabledString = "disabled"
    val CompileOnlyString = "compile-only"
    val FullString = "full"
    def toJson(x: TestingMode): String = x match {
      case Disabled    => DisabledString
      case CompileOnly => CompileOnlyString
      case Full        => FullString
    }
    def fromJson(str: String): TestingMode = str match {
      case DisabledString    => Disabled
      case CompileOnlyString => CompileOnly
      case FullString        => Full
    }
    readwriter[String].bimap[TestingMode](toJson, fromJson)
  }

  implicit lazy val ProjectOverridesR: Reader[ProjectOverrides] = macroR
  implicit lazy val ProjectsConfigR: Reader[ProjectsConfig] = macroR
  implicit lazy val ProjectBuildConfigR: Reader[ProjectBuildConfig] = macroR
}
