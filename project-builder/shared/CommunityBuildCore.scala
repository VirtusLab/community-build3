import scala.collection.JavaConverters._
import java.nio.file.Files
import java.io.File

import TaskEvaluator.EvalResult
import scala.util.matching.Regex

// Community projects configs
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

// Output
case class BuildSummary(results: Seq[ModuleBuildResults]) {
  lazy val toJson: String = results
    .map(_.toJson)
    .mkString("[", ",", "]")
}

case class ModuleBuildResults(
    artifactName: String,
    compile: CompileResult,
    doc: DocsResult,
    testsCompile: CompileResult,
    testsExecute: TestsResult,
    publish: PublishResult
) {
  def hasFailedStep: Boolean = this.productIterator.exists {
    case result: StepResult => result.status == Status.Failed
    case _                  => false
  }
  lazy val toJson = {
    s"""{
       | "module": "$artifactName",
       | "compile": ${compile.toJson},
       | "doc": ${doc.toJson},
       | "test-compile": ${testsCompile.toJson},
       | "test": ${testsExecute.toJson},
       | "publish": ${publish.toJson}
       |}""".stripMargin
  }
}

sealed trait StepResult {
  def status: Status
  def tookMs: Int
  def failureContext: Option[FailureContext]
  def toJson: String

  protected def commonJsonFields: String = {
    val optFailureCtxJson = failureContext.fold("")(ctx => s""""failureContext": ${ctx.toJson}, """)
    s""""status": ${status.toJson}, ${optFailureCtxJson}"tookMs": ${tookMs}"""
  }
}
case class CompileResult(
    status: Status,
    failureContext: Option[FailureContext] = None,
    warnings: Int,
    errors: Int,
    tookMs: Int
) extends StepResult {
  def toJson =
    s"""{$commonJsonFields, "warnings": ${warnings}, "errors": ${errors}}"""
}

case class DocsResult(
    status: Status,
    failureContext: Option[FailureContext] = None,
    files: Int,
    totalSizeKb: Int,
    tookMs: Int
) extends StepResult {
  def toJson =
    s"""{$commonJsonFields, "files": ${files}, "totalSizeKb": ${totalSizeKb}}"""
}
object DocsResult {
  def apply(evalResult: EvalResult[File]): DocsResult = {
    evalResult match {
      case EvalResult.Value(targetDir, tookTime) if targetDir.exists() =>
        case class Stats(files: Int, totalSize: Long)
        val stats = Files
          .walk(targetDir.toPath())
          .filter(Files.isRegularFile(_))
          .iterator()
          .asScala
          .foldLeft(Stats(0, 0L)) { case (stats, path) =>
            stats.copy(
              files = stats.files + 1,
              totalSize = stats.totalSize + Files.size(path)
            )
          }
        DocsResult(
          Status.Ok,
          files = stats.files,
          totalSizeKb = (stats.totalSize / 1024L).toInt,
          tookMs = tookTime
        )
      case _ =>
        DocsResult(
          evalResult.toStatus,
          failureContext = evalResult.toBuildError,
          files = 0,
          totalSizeKb = 0,
          tookMs = evalResult.evalTime
        )
    }
  }
}
case class TestsResult(
    status: Status,
    failureContext: Option[FailureContext] = None,
    passed: Int,
    failed: Int,
    ignored: Int,
    skipped: Int,
    tookMs: Int
) extends StepResult {
  def toJson =
    s"""{$commonJsonFields, "passed": ${passed}, "failed": ${failed}, "ignored": ${ignored}, "skipped": ${skipped}}"""
}
case class PublishResult(status: Status, failureContext: Option[FailureContext] = None, tookMs: Int)
    extends StepResult {
  def toJson = s"""{$commonJsonFields}"""
}
object PublishResult {
  def apply(evalResult: TaskEvaluator.EvalResult[Unit]): PublishResult = {
    PublishResult(
      evalResult.toStatus,
      failureContext = evalResult.toBuildError,
      tookMs = evalResult.evalTime
    )
  }
}

sealed abstract class Status(stringValue: String) {
  def toJson: String = s""""$stringValue""""
}
object Status {
  case object Ok extends Status("ok")
  case object Skipped extends Status("skipped")
  case object Failed extends Status("failed")
}

sealed trait FailureContext {
  def toJson: String
}
object FailureContext {
  case class WrongVersion(expected: String, actual: String) extends FailureContext {
    override def toJson: String =
      s"""{"type": "wrongVersion", "expected": "$expected", "actual": "$actual"}"""
  }
  case class BuildError(reasons: List[String]) extends FailureContext {
    override def toJson: String =
      s"""{"type": "buildError", "reasons": ${reasons.mkString("[", ", ", "]")}}"""
  }
}

object TaskEvaluator {
  type Milliseconds = Int
  sealed trait EvalResult[+T] {
    def evalTime: Milliseconds

    def map[U](fn: T => U): EvalResult[U] = this match {
      case EvalResult.Value(value, tookMs) => EvalResult.Value(fn(value), tookMs)
      case other                           => other.asInstanceOf[EvalResult[U]]
    }

    def toStatus: Status = this match {
      case _: EvalResult.Value[_] => Status.Ok
      case _: EvalResult.Failure  => Status.Failed
      case EvalResult.Skipped     => Status.Skipped
    }

    def toBuildError: Option[FailureContext.BuildError] = this match {
      case EvalResult.Failure(reasons, _) =>
        Some(
          FailureContext.BuildError(
            reasons
              .map(v => "\"" + v.toString.replace("\n", " \\n") + "\"")
              .distinct
          )
        )
      case _ => None
    }
  }
  object EvalResult {
    case class Value[+T](value: T, evalTime: Milliseconds) extends EvalResult[T]
    case class Failure(reasons: List[Throwable], evalTime: Milliseconds) extends EvalResult[Nothing]
    case object Skipped extends EvalResult[Nothing] {
      override final val evalTime: Milliseconds = 0
    }
    def skipped: EvalResult[Nothing] = Skipped
  }

  sealed trait EvaluationException extends Exception
  case class UnkownTaskException(taskName: String) extends EvaluationException
  case class EvaluationFailure(msg: String) extends EvaluationException
}

abstract class TaskEvaluator[Task[_]] {
  import TaskEvaluator._
  def eval[T](task: Task[T]): EvalResult[T]

  def evalWhen[T](predicate: => Boolean, dependencies: EvalResult[_]*)(
      task: Task[T]
  ): EvalResult[T] = {
    if (predicate) evalAsDependencyOf(dependencies: _*)(task)
    else EvalResult.Skipped
  }

  def evalAsDependencyOf[T](
      dependencies: EvalResult[_]*
  )(task: Task[T]): EvalResult[T] = {
    val shouldSkip = dependencies.exists {
      case _: EvalResult.Value[_] => false
      case _                      => true
    }
    if (shouldSkip) EvalResult.Skipped
    else eval(task)
  }
}

class ProjectBuildFailureException
    extends Exception("At least 1 subproject finished with failures") {
  // Don't collect stack trace
  override def fillInStackTrace(): Throwable = this
}

object Utils {
  def filterTargets(targets: Seq[String], excludedPatterns: Seq[Regex]) = {
    targets.filter { target =>
      target.split('%') match {
        case Array(org, name) =>
          val excludingPattern = excludedPatterns.find { pattern =>
            // No Regex.matches in Scala 2.12 (!sic)
            pattern
              .findFirstIn(name)
              .orElse(pattern.findFirstIn(target))
              .isDefined
          }
          excludingPattern.foreach { pattern =>
            println(s"Excluding target '$target' - matches exclusion rule: '${pattern}'")
          }
          excludingPattern.isEmpty
        case _ =>
          println(s"Excluding target '$target' - incompatible format")
          false
      }
    }
  }
}
