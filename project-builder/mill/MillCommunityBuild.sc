import scala.reflect.ClassTag
import scalalib._
import mill.eval._
import mill.define.{Cross => DefCross, _}
import mill.define.Segment._
import requests._
import coursier.maven.MavenRepository
import coursier.Repository

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
def runBuild(targets: Seq[String])(implicit ctx: Ctx) = {
  class TaskEvaluator(module: Module) {
    def eval(labels: String*) = {
      lazy val renderedPath = s"$module.${Segments(labels.map(Label(_)): _*).render}"

      tryEval(module, labels: _*)
        .fold[BuildStepResult] {
          // Target not found, skip it and treat as successfull
          ctx.log.error(s"Not found target '$renderedPath', skipping.")
          BuildError(s"Not found target '$renderedPath'")
        } {
          case Result.Success(v) =>
            ctx.log.info(s"Successfully evaluated $renderedPath")
            Ok
          case failure =>
            ctx.log.error(s"Failed to evaluated $renderedPath: ${failure}")
            Failed
        }
    }
    def evalAsDependencyOf(
        dependecyOf: => BuildStepResult
    )(labels: String*): BuildStepResult = {
      dependecyOf match {
        case _: FailedBuildStep => Skipped
        case _                  => eval(labels: _*)
      }
    }
  }

  val mappings = checkedModuleMappings(targets.toSet)
  val topLevelModules = mappings.collect {
    case (target, info) if targets.contains(target) => info
  }.toSet
  val moduleDeps: Map[Module, Seq[ModuleInfo]] =
    ctx.root.millInternal.modules.collect { case module: JavaModule =>
      val mapped = module.moduleDeps.flatMap(toMappingInfo).map(_._2)
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
    ModuleInfo(org, name, module) <- flatten(topLevelModules, topLevelModules)
  } yield {
    ctx.log.info(s"Starting build for $name")
    val evaluator = new TaskEvaluator(module)
    import evaluator._

    val compileResult = eval("compile")
    val results = ModuleTargetsResults(
      compile = compileResult,
      testsCompile = evalAsDependencyOf(compileResult)("test", "compile"),
      publish = ctx.publishVersion.fold[BuildStepResult](Skipped) { publishVersion =>
        tryEval(module, "publishVersion")
          .fold[BuildStepResult](BuildError("No task 'publishVersion'")) {
            case Result.Success(`publishVersion`) => eval("publishCommunityBuild")
            case Result.Success(version: String) =>
              WrongVersion(expected = publishVersion, got = version)
            case _ => BuildError("Failed to resolve 'publishVersion'")
          }
      }
    )
    ModuleBuildResults(
      organization = org,
      artifactName = name,
      moduleName = module.toString,
      results = results
    )
  }

  val buildSummary = projectsBuildResults
    .map(_.toJson)
    .mkString("{", ", ", "}")
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

private def toMappingInfo(module: Module)(implicit ctx: Ctx): Option[(String, ModuleInfo)] = {
  for {
    Result.Success(publish.Artifact(org, _, _)) <- tryEval(module, "artifactMetadata")
    Result.Success(name: String) <- tryEval(module, "artifactName")
    info = ModuleInfo(org, name, module)
  } yield info.targetName -> info
}

type ModuleMappings = Map[String, ModuleInfo]
def moduleMappings(implicit ctx: Ctx): ModuleMappings = {
  ctx.root.millInternal.modules.flatMap { module =>
    for {
      Result.Success(scalaVersion) <- tryEval(module, "scalaVersion")
      if scalaVersion == ctx.scalaVersion

      Result.Success(platformSuffix: String) <- tryEval(module, "platformSuffix")
      if platformSuffix.isEmpty // is JVM

      mapping @ (targetName, ModuleInfo(_, _, module)) <- toMappingInfo(module)
    } yield mapping
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
private def tryEval(module: Module, labels: String*)(implicit ctx: Ctx) = {
  val rootSegments =
    module.millOuterCtx.segments.value :+
      module.millOuterCtx.segment
  val moduleCommands =
    try {
      module.millInternal
        .traverse(_.millInternal.reflectAll[mill.define.Command[_]])
        .map(cmd => cmd.ctx.segments -> cmd)
        .toMap
    } catch {
      case ex =>
        ctx.log.error(s"Exception when trying to resolve commands for $module")
        Map.empty
    }

  (module.millInternal.segmentsToTargets ++ moduleCommands)
    .get(Segments((rootSegments ++ labels.map(Label(_))): _*))
    .map { task =>
      val evalState = ctx.evaluator.evaluate(Agg(task))
      val failure = evalState.failing.values.flatten.toSeq.headOption
      def result = evalState.rawValues.head
      failure.getOrElse(result)
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
    testsCompile: BuildStepResult,
    publish: BuildStepResult
) {
  def hasFailedStep: Boolean = this.productIterator.exists(_.isInstanceOf[FailedBuildStep])
  def jsonValues: List[String] = List(
    "compile" -> compile,
    "test-compile" -> testsCompile,
    "publish" -> publish
  ).map { case (key, value) =>
    s""""$key": "${value.stringValue}""""
  }
}
case class ModuleBuildResults(
    moduleName: String,
    organization: String,
    artifactName: String,
    results: ModuleTargetsResults
) {
  lazy val toJson = {
    val resultsJson = results.jsonValues.mkString(", ")
    val metadata = s""""meta": {"organization": "$organization", "moduleName": "$moduleName"}"""
    s""""$artifactName": {$resultsJson, $metadata}"""
  }
}

class ProjectBuildFailureException extends Exception
