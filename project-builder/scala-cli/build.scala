//> using toolkit latest
//> using scala 3
//> using file ../shared/CommunityBuildCore.scala

import Scala3CommunityBuild.*
import java.nio.file.Paths

import uPickleSerializers.OptionPickler.read
import TaskEvaluator.EvalResult
import os.CommandResult

@main def buildScalaCliProject(
    repositoryDir: String,
    scalaVersion: String,
    configJson: String
): Unit = {
  println(s"Build config: ${configJson}")
  val config = read[ProjectBuildConfig](configJson)
  println(s"Parsed config: ${config}")

  val evaluator = CliTaskEvaluator(scalaVersion, repositoryDir)
  import evaluator.{eval, evalAsDependencyOf, evalWhen}

  val compileResult = eval[Unit](cmd("compile"))
  val docResult = evalAsDependencyOf(compileResult)("doc", "--force")
  val testsCompileResult =
    evalWhen[Unit](config.tests != TestingMode.Disabled, compileResult)(
      cmd("compile", "--test")
    )
  val testsExecuteResults =
    evalWhen[Unit](config.tests == TestingMode.Full, compileResult)(
      cmd("test").copy(errHandler =
        (proc, failure) =>
          if (proc.err.toString().contains("No test framework found"))
            EvalResult.Skipped
          else failure
      )
    )
  val publishResult = PublishResult(Status.Skipped, tookMs = 0)

  def collectCompileResults(evalResult: EvalResult[Unit]): CompileResult =
    CompileResult(
      evalResult.toStatus,
      failureContext = evalResult.toBuildError,
      warnings = 0,
      errors = 0,
      tookMs = evalResult.evalTime
    )

  val projectResults = ModuleBuildResults(
    artifactName = "",
    compile = collectCompileResults(compileResult),
    doc = DocsResult(
      docResult.map(_ => Paths.get(repositoryDir, "scala-doc").toAbsolutePath().toFile())
    ),
    testsCompile = collectCompileResults(testsCompileResult),
    testsExecute = TestsResult(
      status = testsExecuteResults.toStatus,
      failureContext = testsExecuteResults.toBuildError,
      passed = 0,
      failed = 0,
      ignored = 0,
      skipped = 0,
      tookMs = testsExecuteResults.evalTime
    ),
    publish = publishResult
  )

  val projectsBuildResults = projectResults :: Nil
  val buildSummary = BuildSummary(projectsBuildResults)

  println(s"""
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

case class CliCommand[T](
    command: Seq[String],
    errHandler: (CommandResult, EvalResult.Failure) => EvalResult[T]
)
def cmd(args: String*) = CliCommand[Unit](args, (_, failure) => failure)
class CliTaskEvaluator(scalaVersion: String, repositoryDir: String)
    extends TaskEvaluator[CliCommand] {
  import TaskEvaluator.*

  def evalAsDependencyOf(
      dependencies: EvalResult[_]*
  )(command: String*): EvalResult[Unit] = {
    val shouldSkip = dependencies.exists {
      case _: EvalResult.Value[_] => false
      case _                      => true
    }
    if (shouldSkip) EvalResult.Skipped
    else eval(cmd(command*))
  }

  def eval[T](task: CliCommand[T]): EvalResult[T] = {
    val evalStart = System.currentTimeMillis()
    val proc = os
      .proc(
        "scala-cli",
        "--power",
        task.command,
        repositoryDir,
        s"--scala-version=${scalaVersion}"
      )
      .call(check = false, stderr = os.Pipe)
    val result = proc.exitCode
    val tookMillis = (System.currentTimeMillis() - evalStart).toInt
    def nullT = null.asInstanceOf[T]
    result match {
      case 0 =>
        println(s"Successfully evaluated $task")
        EvalResult.Value(nullT, evalTime = tookMillis)
      case exitCode =>
        println(s"Failed to evaluated $task: exitCode ${exitCode}")
        proc.err.lines().foreach(System.err.println)
        val failure = EvalResult.Failure(
          EvaluationFailure(proc.err.toString()) :: Nil,
          evalTime = tookMillis
        )
        task.errHandler(proc, failure)
    }
  }
}

object uPickleSerializers {
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
