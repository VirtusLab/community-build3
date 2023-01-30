import $file.CommunityBuildCore, CommunityBuildCore.Scala3CommunityBuild.{
  TestingMode => _,
  ProjectBuildConfig => _,
  ProjectOverrides => _,
  _
}
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
import scalalib._
import scalalib.api.CompilationResult
import mill.eval._
import mill.define.{Cross => DefCross, _}
import mill.define.Segment._
import requests._
import coursier.maven.MavenRepository
import coursier.Repository

private object compat {
  // In Mill 0.10.x series mill.scalalib.TestRunner was moved to mill.testrunner
  // We cannot enforce 0.10.x series, becouse multiple builds use EOL ammonite-ops, which are absent in 0.10.x
  val MillTestRunner = {
    import CompatDef._ // introduces alternative testrunner from ComaptDef
    {
      import mill._ // in 0.10.x replaces CompatDef.testrunner with mill.testrunnner
      import testrunner._ // Adds TestRunner to the scope from either scope
      {
        import mill.scalalib._ // In 0.9.x replaces CompatDef.testruner.TestRunner with mill.scalalib.TestRunner
        TestRunner
      }
    }
  }

  object CompatDef {
    object testrunner {
      object TestRunner
    }
  }
}
import compat.{MillTestRunner => TestRunner}

trait CommunityBuildCoursierModule extends CoursierModule { self: JavaModule =>
  protected val mavenRepoUrl: Option[String] = sys.props
    .get("communitybuild.maven.url")
    .map(_.stripSuffix("/"))
  private val mavenRepo = mavenRepoUrl.map(MavenRepository(_))

  override def repositoriesTask: Task[Seq[Repository]] = T.task {
    mavenRepo.foldLeft(super.repositoriesTask())(_ :+ _)
  }
  // Override zinc worker, we need to set custom repostitories there are well,
  // to allow to use our custom repo
  override def zincWorker: ZincWorkerModule = CommunityBuildZincWorker
  object CommunityBuildZincWorker extends ZincWorkerModule with CoursierModule {
    override def repositoriesTask() = T.task {
      mavenRepo.foldLeft(super.repositoriesTask())(_ :+ _)
    }
  }
}

// Extension to publish module allowing to upload artifacts to custom maven repo
trait CommunityBuildPublishModule extends PublishModule with CommunityBuildCoursierModule {
  def publishCommunityBuild() = T.command {
    val PublishModule.PublishData(metadata, artifacts) = publishArtifacts()
    val artifactModulePath = {
      val org = metadata.group.replace(".", "/")
      s"$org/${metadata.id}/${metadata.version}"
    }
    for {
      (artifactPath, artifactName) <- artifacts
      repoUrl <- mavenRepoUrl
      url = s"$repoUrl/$artifactModulePath/$artifactName"
    } {
      val res = put(
        url = url,
        data = RequestBlob.NioFileRequestBlob(artifactPath.path.toNIO),
        verifySslCerts = false
      )
      if (!res.is2xx) {
        throw new RuntimeException(
          s"Failed to publish artifact ${url.stripPrefix(repoUrl)}: ${res.statusMessage}"
        )
      }
    }
  }
}

/** Replace all Scala in crossVersion with `buildScalaVersion` if its matching `buildScalaVersion`
  * binary version
  * @param `crossVersions`
  *   sequence of cross versions, in the form of either `String` or `Product` of Strings (based on
  *   the mill api)
  */
def mapCrossVersions(
    buildScalaVersion: String,
    crossVersions: Seq[Any]
): Seq[Any] = {
  implicit val ctx = ExtractorsCtx(buildScalaVersion)
  for {
    // Map products to lists (List[_] <: Product ) for stable pattern matching
    crossEntry <- crossVersions
    cross = crossEntry match {
      case product: Product => product.productIterator.toList
      case other            => other
    }
    mappedCrossVersion = cross match {
      case MatchesScalaBinaryVersion() => buildScalaVersion
      case List(MatchesScalaBinaryVersion(), crossVersion) =>
        (buildScalaVersion, crossVersion)
      case List(crossVersion, MatchesScalaBinaryVersion()) =>
        (crossVersion, buildScalaVersion)
      case _ => crossEntry
    }
    version <- Seq(crossEntry, mappedCrossVersion).distinct
  } yield {
    if (version != crossEntry) {
      println(s"Use cross-version $version instead of $crossEntry")
    }
    version
  }
}

case class ModuleInfo(org: String, name: String, module: Module) {
  val targetName = s"$org%$name"
}
case class Ctx(
    root: mill.define.Module,
    scalaVersion: String,
    evaluator: Evaluator,
    log: mill.api.Logger
) {
  lazy val publishVersion = sys.props.get("communitybuild.version").filterNot(_.isEmpty)
  lazy val cross = Cross(scalaVersion :: Nil)
}

class MillTaskEvaluator()(implicit ctx: Ctx) extends TaskEvaluator[NamedTask] {
  import TaskEvaluator._
  def eval[T](task: NamedTask[T]): EvalResult[T] = {
    val evalStart = System.currentTimeMillis()
    val result = tryEval(task)
    val tookMillis = (System.currentTimeMillis() - evalStart).toInt
    result match {
      case Result.Success(v) =>
        ctx.log.info(s"Successfully evaluated $task")
        EvalResult.Value(v, evalTime = tookMillis)
      case failure: Result.Failing[_] =>
        ctx.log.error(s"Failed to evaluated $task: ${failure}")
        val reason = failure match {
          case Result.Exception(throwable, _) => throwable
          case Result.Failure(msg, value) =>
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
def runBuild(configJson: String, targets: Seq[String])(implicit ctx: Ctx) = {

  println(s"Build config: ${configJson}")
  val config = read[ProjectBuildConfig](configJson)
  println(s"Parsed config: ${config}")
  val filteredTargets = filterTargets(targets, config.projects.exclude.map(_.r))
  val mappings = checkedModuleMappings(filteredTargets.toSet)
  val topLevelModules = mappings.collect {
    case (target, info) if filteredTargets.contains(target) => info
  }.toSet
  val moduleDeps: Map[Module, Seq[ModuleInfo]] =
    ctx.root.millInternal.modules.collect { case module: PublishModule =>
      val mapped = for {
        module <- module.moduleDeps
        Result.Success((_, mapped)) <- toMappingInfo(module).asSuccess
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

  val projectsBuildResults = for {
    ModuleInfo(org, name, module: ScalaModule) <- flatten(topLevelModules, topLevelModules).toList
  } yield {
    ctx.log.info(s"Starting build for $name")
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
      .collect { case module: mill.scalalib.TestModule => module } match {
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

    val compileResult = eval(module.compile)
    val docResult = evalAsDependencyOf(compileResult)(module.docJar)
    val testsCompileResult =
      test(_.compile).fold[EvalResult[CompilationResult]](EvalResult.skipped) {
        evalWhen(testingMode != TestingMode.Disabled, compileResult)(_)
      }
    val testsExecuteResults =
      test(_.test()).fold[EvalResult[Seq[TestRunner.Result]]](EvalResult.skipped) {
        evalWhen(testingMode == TestingMode.Full, testsCompileResult)(_).map(_._2)
      }
    val publishResult = module match {
      case module: CommunityBuildPublishModule =>
        ctx.publishVersion.fold(PublishResult(Status.Skipped, tookMs = 0)) { publishVersion =>
          tryEval(module.publishVersion) match {
            case Result.Success(`publishVersion`) =>
              PublishResult(
                evalAsDependencyOf(compileResult, docResult)(module.publishCommunityBuild)
              )
            case Result.Success(version: String) =>
              PublishResult(
                Status.Failed,
                failureContext =
                  Some(FailureContext.WrongVersion(expected = publishVersion, actual = version)),
                tookMs = 0
              )
            case _ =>
              PublishResult(
                Status.Failed,
                failureContext =
                  Some(FailureContext.BuildError(List("Failed to resolve 'publishVersion'"))),
                tookMs = 0
              )
          }
        }
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
      publish = publishResult
    )
  }

  val buildSummary = BuildSummary(projectsBuildResults)

  ctx.log.info(s"""
    |************************
    |Build summary:
    |${buildSummary.toJson}
    |************************"
    |""".stripMargin)

  val outputDir = os.pwd / os.up
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

private def collectTestResults(evalResult: EvalResult[Seq[TestRunner.Result]]): TestsResult = {
  import sbt.testing.Status
  val empty = TestsResult(
    status = evalResult.toStatus,
    passed = 0,
    failed = 0,
    ignored = 0,
    skipped = 0,
    tookMs = evalResult.evalTime
  )
  evalResult match {
    case EvalResult.Value(results, tookMs) =>
      // status is a string defined as sbt.testing.Status.toString
      val resultsStatus = results
        .groupBy(_.status)
        .map { case (key, values) => sbt.testing.Status.valueOf(key) -> values.size }

      def countOf(selected: Status*) = selected.foldLeft(0)(_ + resultsStatus.getOrElse(_, 0))

      empty.copy(
        passed = countOf(Status.Success),
        failed = countOf(Status.Error, Status.Failure, Status.Canceled),
        ignored = countOf(Status.Ignored),
        skipped = countOf(Status.Skipped)
      )
    case _ => empty.copy(failureContext = evalResult.toBuildError)
  }
}

private def toMappingInfo(module: JavaModule)(implicit ctx: Ctx): Result[(String, ModuleInfo)] = {
  for {
    org <- module match {
      case m: PublishModule => tryEval(m.pomSettings).map(_.organization)
      case _                =>
        // it's not a published module, we don't need to care about it as it's not going to be enlisted in targets
        Result.Success("")
    }
    name <- tryEval(module.artifactName)
    info = ModuleInfo(org, name, module)
  } yield info.targetName -> info
}

type ModuleMappings = Map[String, ModuleInfo]
def moduleMappings(implicit ctx: Ctx): ModuleMappings = {
  import mill.api.Result.{Success => S}
  ctx.root.millInternal.modules.flatMap {
    case module: mill.scalalib.ScalaModule =>
      // Result does not have withFilter method, wrap it into the Option to allow for-comprehension
      for {
        S(scalaVersion) <- tryEval(module.scalaVersion).asSuccess
        if scalaVersion == ctx.scalaVersion

        S(platformSuffix: String) <- tryEval(module.platformSuffix).asSuccess
        if platformSuffix.isEmpty // is JVM

        S(mapping @ (targetName, ModuleInfo(_, _, module))) <- toMappingInfo(module).asSuccess
      } yield mapping
    case _ =>
      // We're only intrested in Scala modules
      None
  }.toMap
}

private def checkedModuleMappings(
    targetStrings: Set[String]
)(implicit ctx: Ctx): ModuleMappings = {
  val mappings = moduleMappings(ctx)
  val unmatched = targetStrings.diff(mappings.keySet)
  if (unmatched.nonEmpty) {
    sys.error(
      s"Failed to resolve mappings for targets: ${unmatched.mkString(", ")}"
    )
  }
  mappings
}

// Evaluation of mill targets/commands
private def tryEval[T](task: NamedTask[T])(implicit ctx: Ctx): Result[T] = {
  val evalState = ctx.evaluator.evaluate(Agg(task))
  val failure = evalState.failing.values.flatten.toSeq.headOption
  def result = evalState.rawValues.head
  failure.getOrElse(result).asInstanceOf[Result[T]]
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
