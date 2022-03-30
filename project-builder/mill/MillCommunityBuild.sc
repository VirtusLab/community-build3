import scala.reflect.ClassTag
import scalalib._
import mill.eval._
import mill.define.{Cross => DefCross, _}
import mill.define.Segment._
import requests._
import coursier.maven.MavenRepository
import coursier.Repository
import OptionPickler.{read, ReadWriter, readwriter, macroRW}

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
  override def zincWorker = CommunityBuildZincWorker
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
  } yield {
    if (mappedCrossVersion != crossEntry) {
      println(s"Use cross-version $mappedCrossVersion instead of $crossEntry")
    }
    mappedCrossVersion
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

// Main entry point for Mill community build
// Evaluate tasks until first failure and publish report
def runBuild(configJson: String, targets: Seq[String])(implicit ctx: Ctx) = {
  class TaskEvaluator() {
    def eval(task: NamedTask[_]) = {
      tryEval(task) match {
        case Result.Success(v) =>
          ctx.log.info(s"Successfully evaluated $task")
          Ok
        case failure =>
          ctx.log.error(s"Failed to evaluated $task: ${failure}")
          Failed
      }
    }
    def evalAsDependencyOf(task: NamedTask[_])(
        dependencies: BuildStepResult*
    ): BuildStepResult = {
      val shouldSkip = dependencies.exists {
        case _: FailedBuildStep => true
        case Skipped            => true
        case _                  => false
      }
      if (shouldSkip) Skipped
      else eval(task)
    }
    def evalAsDependencyOf(optTask: Option[NamedTask[_]])(
        dependencies: BuildStepResult*
    ): BuildStepResult = {
      optTask.fold[BuildStepResult](Skipped)(evalAsDependencyOf(_)(dependencies: _*))
    }
  }

  println(s"Build config: ${configJson}")
  val config = read[ProjectBuildConfig](configJson)
  println(s"Parsed config: ${config}")
  val filteredTargets = {
    val excludedPatterns = config.projects.exclude.map(_.r)
    targets.filter { id =>
      id.split('%') match {
        case Array(org, name) =>
          val excludingPattern = excludedPatterns.find { pattern =>
            // No Regex.matches in Scala 2.12 (!sic)
            pattern
              .findFirstIn(name)
              .orElse(pattern.findFirstIn(id))
              .isDefined
          }
          excludingPattern.foreach { pattern =>
            println(s"Excluding target '$id' - matches exclusion rule: '${pattern}'")
          }
          excludingPattern.isEmpty
        case _ =>
          println(s"Excluding target '$id' - incompatible format")
          false
      }
    }
  }
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
    ModuleInfo(org, name, module: ScalaModule) <- flatten(topLevelModules, topLevelModules)
  } yield {
    ctx.log.info(s"Starting build for $name")
    val evaluator = new TaskEvaluator()
    import evaluator._
    val overrides = config.projects.overrides.get(name)
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
    def test(selector: TestModule => NamedTask[_]): Option[NamedTask[_]] =
      testModule.map(selector)

    val compileResult = eval(module.compile)
    val docResult = evalAsDependencyOf(module.docJar)(compileResult)
    val testsCompileResult = testingMode match {
      case TestingMode.Disabled => Skipped
      case _                    => evalAsDependencyOf(test(_.compile))(compileResult)
    }
    val testsExecuteResults = testingMode match {
      case TestingMode.Full => evalAsDependencyOf(test(_.test()))(testsCompileResult)
      case _                => Skipped
    }
    val publishResult = module match {
      case module: CommunityBuildPublishModule =>
        ctx.publishVersion.fold[BuildStepResult](Skipped) { publishVersion =>
          tryEval(module.publishVersion) match {
            case Result.Success(`publishVersion`) =>
              evalAsDependencyOf(module.publishCommunityBuild)(compileResult, docResult)
            case Result.Success(version: String) =>
              WrongVersion(expected = publishVersion, got = version)
            case _ => BuildError("Failed to resolve 'publishVersion'")
          }
        }
      case _ =>
        ctx.log.error(s"Module $module is not a publish module, skipping publishing")
        Skipped
    }
    val results = ModuleTargetsResults(
      compile = compileResult,
      doc = docResult,
      testsCompile = testsCompileResult,
      testsExecute = testsExecuteResults,
      publish = publishResult
    )
    ModuleBuildResults(
      artifactName = name,
      results = results
    )
  }

  val buildSummary = projectsBuildResults
    .map(_.toJson)
    .mkString("[", ",", "]")
  ctx.log.info(s"""
    |************************
    |Build summary:
    |$buildSummary
    |************************"
    |""".stripMargin)

  val outputDir = os.pwd / os.up
  os.write.over(outputDir / "build-summary.txt", buildSummary)

  val hasFailedSteps = projectsBuildResults.exists(_.results.hasFailedStep)
  val buildStatus =
    if (hasFailedSteps) "failure"
    else "success"
  os.write.over(outputDir / "build-status.txt", buildStatus)
  if (hasFailedSteps) {
    throw new ProjectBuildFailureException
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

sealed abstract class BuildStepResult(val stringValue: String) {
  def jsonValue = s""""$stringValue""""
}
sealed abstract class FailedBuildStep(name: String, info: List[(String, String)])
    extends BuildStepResult(name) {
  override def jsonValue =
    if (info.isEmpty) s""""$name""""
    else {
      val infoFields = info.map { case (k, v) => s""""$k": "$v"""" }.mkString(", ")
      s""""$name": {$infoFields}"""
    }
}
case object Ok extends BuildStepResult("ok")
case object Skipped extends BuildStepResult("skipped")
case object Failed extends FailedBuildStep("failed", Nil)
case class WrongVersion(expected: String, got: String)
    extends FailedBuildStep("wrongVersion", List("expected" -> expected, "got" -> got))
case class BuildError(msg: String) extends FailedBuildStep("buildError", List("reason" -> msg))

case class ModuleTargetsResults(
    compile: BuildStepResult,
    doc: BuildStepResult,
    testsCompile: BuildStepResult,
    testsExecute: BuildStepResult,
    publish: BuildStepResult
) {
  def hasFailedStep: Boolean = this.productIterator.exists(_.isInstanceOf[FailedBuildStep])
  def jsonValues: List[String] = List(
    "compile" -> compile,
    "doc" -> doc,
    "test-compile" -> testsCompile,
    "test" -> testsExecute,
    "publish" -> publish
  ).map { case (key, value) =>
    s""""$key": "${value.stringValue}""""
  }
}
case class ModuleBuildResults(
    artifactName: String,
    results: ModuleTargetsResults
) {
  lazy val toJson = {
    val resultsJson = results.jsonValues.mkString(", ")
    s"""{
       | "module": "$artifactName",
       | $resultsJson 
       |}""".stripMargin
  }
}

class ProjectBuildFailureException extends Exception

sealed trait TestingMode
object TestingMode {
  case object Disabled extends TestingMode
  case object CompileOnly extends TestingMode
  case object Full extends TestingMode

  implicit val rw: ReadWriter[TestingMode] = {
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
}

// Used to asssume coding of Option as nulls, instead of arrays (default)
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

case class ProjectOverrides(tests: Option[TestingMode])
object ProjectOverrides {
  implicit val rw: ReadWriter[ProjectOverrides] = macroRW
}

case class ProjectsConfig(
    exclude: List[String] = Nil,
    overrides: Map[String, ProjectOverrides] = Map.empty
)
object ProjectsConfig {
  implicit val rw: ReadWriter[ProjectsConfig] = macroRW
}

case class ProjectBuildConfig(
    projects: ProjectsConfig = ProjectsConfig(),
    tests: TestingMode = TestingMode.Full
)
object ProjectBuildConfig {
  implicit val rw: ReadWriter[ProjectBuildConfig] = macroRW
}
