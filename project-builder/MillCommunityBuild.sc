import scala.reflect.ClassTag
import scalalib._
import mill.eval._
import mill.define.{Cross => DefCross, _}
import mill.define.Segment._
import requests._

// Extension to publish module allowing to upload artifacts to custom maven repo
trait CommunityBuildPublishModule extends PublishModule { outer =>
  def publishCommunityBuild() = T.command {
    val mavenRepoUrl: String = sys.props
      .get("communitybuild.maven.url")
      .map(_.stripSuffix("/"))
      .getOrElse {
        sys.error("Required property 'communitybuild.maven.url' not set")
      }

    val PublishModule.PublishData(metadata, artifacts) = publishArtifacts()
    val artifactModulePath = {
      val org = metadata.group.replace(".", "/")
      s"$org/${metadata.id}/${metadata.version}"
    }
    for {
      (artifactPath, artifactName) <- artifacts
      url = s"$mavenRepoUrl/$artifactModulePath/$artifactName"
    } {
      val res = put(
        url = url,
        data = RequestBlob.NioFileRequestBlob(artifactPath.path.toNIO),
        verifySslCerts = false
      )
      if (!res.is2xx) {
        throw new RuntimeException(
          s"Failed to publish artifact ${url.stripPrefix(mavenRepoUrl)}: ${res.statusMessage}"
        )
      }
    }
  }
}

/** Replace all Scala in crossVersion with `buildScalaVersion` if its matching
  * `buildScalaVersion` binary version
  * @param `crossVersions`
  *   sequence of cross versions, in the form of either `String` or `Product` of
  *   Strings (based on the mill api)
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

private case class ModuleInfo(name: String, module: Module)
case class Ctx(
    root: mill.define.Module,
    scalaVersion: String,
    evaluator: Evaluator,
    log: mill.api.Logger
) {
  lazy val publishVersion = sys.props.get("communitybuild.version")
  lazy val cross = Cross(scalaVersion :: Nil)
}

// Main entry point for Mill community build
// Evaluate tasks until first failure and publish report
def runBuild(targets: Seq[String])(implicit ctx: Ctx) = {
  def buildTasks(moduleInfo: ModuleInfo): LazyList[BuildStepResult] = {
    val ModuleInfo(name, module) = moduleInfo
    Info(name) #::
      mapEval(module, "compile")(CompileFailed)(CompileOk) #::
      mapEval(module, "test", "compile")(TestFailed)(TestOk) #::
      // mapEval(module, "test", "testCached")(TestFailed)(TestOk) #::
      ctx.publishVersion
        .fold[BuildStepResult](PublishSkipped) { publishVersion =>
          evalOrThrow(module, "publishVersion") match {
            case Result.Success(`publishVersion`) =>
              mapEval(module, "publishCommunityBuild")(PublishFailed)(PublishOk)
            case Result.Success(version: String) =>
              PublishWrongVersion(Some(version))
            case _ => PublishFailed
          }
        } #:: LazyList.empty
  }

  val mappings = moduleMappings(targets.toSet)
  val projectsBuildResults = for {
    target <- targets
    moduleInfo = mappings(target)
    tasks = buildTasks(moduleInfo)
  } yield {
    ctx.log.info(s"Starting build for ${moduleInfo.name}")
    evalAndTakeUntilFailure(tasks)
  }

  val buildSummary = projectsBuildResults
    .map {
      case List(result) => result.asString
      case subProjectName :: subProjectResults =>
        val subProject = subProjectName.asString
        val results = subProjectResults.map(_.asString).mkString("{", ", ", "}")
        s"$subProject: $results"
    }
    .mkString("{", ", ", "}")

  ctx.log.info(s"""
    |************************
    |Build summary:
    |$buildSummary
    |************************"
    |""".stripMargin)

  val outputDir = os.pwd / os.up
  os.write.over(outputDir / "build-summary.txt", buildSummary)

  val hasFailedSteps =
    projectsBuildResults.exists(_.exists(_.isInstanceOf[BuildStepFailure]))
  val buildStatus = if (!hasFailedSteps) "success" else "failure"
  os.write.over(outputDir / "build-status.txt", buildStatus)
  if (hasFailedSteps) {
    throw new ProjectBuildFailureException
  }
}

private def moduleMappings(
    targetStrings: Set[String]
)(implicit ctx: Ctx): Map[String, ModuleInfo] = {
  val mappings = ctx.root.millInternal.modules.flatMap { module =>
    for {
      Result.Success(scalaVersion) <- tryEval(module, "scalaVersion")
      if scalaVersion == ctx.scalaVersion

      Result
        .Success(platformSuffix: String) <- tryEval(module, "platformSuffix")
      if platformSuffix.isEmpty // is JVM

      Result.Success(publish.Artifact(org, _, _)) <- tryEval(
        module,
        "artifactMetadata"
      )
      Result.Success(name: String) <- tryEval(module, "artifactName")
      targetName = s"$org%$name"
      _ = ctx.log.info(s"Using $module for $targetName")
    } yield targetName -> ModuleInfo(name, module)
  }.toMap

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

private def mapEval(
    module: Module,
    labels: String*
)(
    onFailure: => BuildStepResult
)(onSucces: => BuildStepResult)(implicit ctx: Ctx) = {
  lazy val renderedPath =
    s"$module.${Segments(labels.map(Label(_)): _*).render}"

  tryEval(module, labels: _*)
    .fold {
      sys.error(s"Failed to eval $renderedPath")
    } {
      case Result.Success(v) =>
        ctx.log.info(s"Successfully evaluated $renderedPath")
        onSucces
      case failure =>
        ctx.log.error(s"Failed to evaluated $renderedPath: ${failure}")
        onFailure
    }
}

private def evalOrThrow(module: Module, segments: String*)(implicit
    ctx: Ctx
) = {
  val segmentsAsLabels = segments.map(Label(_))
  tryEval(module, segments: _*)
    .getOrElse {
      val path = Segments(
        (List(Label(module.toString), ctx.cross) ++ segmentsAsLabels): _*
      ).render
      sys.error(s"Failed to eval $path")
    }
}

private def evalAndTakeUntilFailure(
    tasks: LazyList[BuildStepResult]
)(implicit ctx: Ctx): List[BuildStepResult] = {
  import scala.util._
  @scala.annotation.tailrec
  def loop(
      tasks: LazyList[BuildStepResult],
      acc: List[BuildStepResult] = Nil
  ): List[BuildStepResult] = {
    // Evaluate head of lazy list, in case of exception wrap it into the BuildError
    val eval = Try(tasks.headOption).recover { ex: Throwable =>
      ctx.log.error(
        s"Error when evaluationg ${tasks.head}, $ex - ${ex.getMessage}"
      )
      Some(BuildError(ex.getMessage))
    }.get
    eval match {
      case None                         => acc.reverse
      case Some(fail: BuildStepFailure) => loop(LazyList(), fail :: acc)
      case Some(result)                 => loop(tasks.tail, result :: acc)
    }
  }
  loop(tasks)
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

// Step results
abstract class BuildStepResult(val asString: String)
abstract class BuildStepFailure(stringValue: String) extends BuildStepResult(stringValue)
abstract class BuildStepSuccess(stringValue: String) extends BuildStepResult(stringValue)

case class Info(msg: String) extends BuildStepSuccess(s""""$msg"""")
object CompileOk extends BuildStepSuccess(""""compile": "ok"""")
object CompileFailed extends BuildStepFailure(""""compile": "failed"""")
object TestOk extends BuildStepSuccess(""""test": "ok"""")
object TestFailed extends BuildStepFailure(""""test": "failed"""")
object PublishSkipped extends BuildStepSuccess(""""publish": "skipped"""")
case class PublishWrongVersion(version: Option[String]) extends BuildStepFailure(s""""publish": "wrongVersion=${version}"""")
object PublishOk extends BuildStepSuccess(""""publish": "ok"""")
object PublishFailed extends BuildStepFailure(""""publish": "failed"""")
case class BuildError(msg: String) extends BuildStepFailure(s""""error": "${msg}"""")

class ProjectBuildFailureException extends Exception
