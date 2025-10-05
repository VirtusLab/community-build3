import scala.collection.JavaConverters._
import java.nio.file.Files
import java.io.File

import scala.util.matching.Regex
import scala.language.higherKinds

// Wrap into object instead of package becouse mill does not handle packages in ammonite files
object Scala3CommunityBuild {
  import TaskEvaluator.EvalResult

  // Community projects configs
  case class ProjectBuildConfig(
      projects: ProjectsConfig = ProjectsConfig(),
      tests: TestingMode = TestingMode.Full,
      sourcePatches: Seq[SourcePatch] = Nil
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

  case class SourcePatch(
      path: String,
      pattern: String,
      replaceWith: String,
      selectVersion: Option[ScalaVersionRange] = None,
  )
  case class ScalaVersionRange(
    min: Option[String] = None,
    max: Option[String] = None,
  )

// Output
  case class BuildSummary(results: Seq[ModuleBuildResults]) {
    lazy val toJson: String = results
      .map(_.toJson)
      .mkString("[", ",", "]")
  }

  case class ModuleMetadata(
      crossScalaVersions: Seq[String]
  ) {
    lazy val toJson = {
      def quoted(v: String) = "\"" + v + "\""
      s"""{
      |  "crossScalaVersions": [${crossScalaVersions.map(quoted).mkString(", ")}]
      |}""".mkString
    }
  }
  case class ModuleBuildResults(
      artifactName: String,
      compile: CompileResult,
      doc: DocsResult,
      testsCompile: CompileResult,
      testsExecute: TestsResult,
      publish: PublishResult,
      metadata: ModuleMetadata
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
       | "publish": ${publish.toJson},
       | "metadata": ${metadata.toJson}
       |}""".stripMargin
    }
  }

  sealed trait StepResult {
    def status: Status
    def tookMs: Int
    def failureContext: Option[FailureContext]
    def toJson: String

    protected def commonJsonFields: String = {
      val optFailureCtxJson =
        failureContext.fold("")(ctx => s""""failureContext": ${ctx.toJson}, """)
      s""""status": ${status.toJson}, ${optFailureCtxJson}"tookMs": ${tookMs}"""
    }
  }
  case class CompileResult(
      status: Status,
      failureContext: Option[FailureContext] = None,
      warnings: Int,
      errors: Int,
      tookMs: Int,
      sourceVersion: Option[String] = None
  ) extends StepResult {
    def toJson = {
      val optionals = Seq(
        sourceVersion.map(v => s""""sourceVersion": "$v"""")
      ).flatten
      val optionalsString =
        if (optionals.isEmpty) ""
        else optionals.mkString(", ", ", ", "")
      s"""{$commonJsonFields, "warnings": ${warnings}, "errors": ${errors}$optionalsString}"""
    }
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
  case class TestStats(passed: Int, failed: Int, ignored: Int, skipped: Int) {
    val total: Int = passed + failed + ignored + skipped
    def toJson(inlined: Boolean = false) = {
      val raw =
        s""""passed": ${passed}, "failed": ${failed}, "ignored": ${ignored}, "skipped": ${skipped}, "total": ${total}"""
      if (inlined) raw
      else s"{$raw}"
    }
  }
  object TestStats {
    val empty = TestStats(0, 0, 0, 0)
  }
  case class TestsResult(
      status: Status,
      tookMs: Int,
      failureContext: Option[FailureContext] = None,
      overall: TestStats = TestStats.empty,
      byFramework: Map[String, TestStats] = Map.empty
  ) extends StepResult {
    def toJson = {
      val byFrameworkJson = byFramework.toSeq
        .sortBy(_._1)
        .map { case (name, stats) =>
          s"""{"framework": "$name", "stats": ${stats.toJson()}}"""
        }
        .mkString("[", ",", "]")
      s"""{$commonJsonFields, ${overall.toJson(inlined =
        true
      )}, "byFramework": ${byFrameworkJson}}"""
    }
  }
  case class PublishResult(
      status: Status,
      failureContext: Option[FailureContext] = None,
      tookMs: Int
  ) extends StepResult {
    def toJson = s"""{$commonJsonFields}"""
  }
  object PublishResult {
    def apply(evalResult: TaskEvaluator.EvalResult[Unit]): PublishResult = {
      val failureContext = evalResult.toBuildError
      val status = failureContext match {
        case Some(FailureContext.BuildError(reasons))
            if reasons.exists(_.contains("java.util.NoSuchElementException")) =>
          Status.Skipped
        case _ => evalResult.toStatus
      }
      PublishResult(
        status,
        failureContext = failureContext,
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
      override def toJson: String = {
        // Used to match output colored using scala.io.AnsiColor
        // ; is optional, it is not a part of AnsiColor, but is allowed in general to specify both foreground and background color
        val AnsiColorPattern = raw"\u001B\[[;\d]*m"
        val reasonsArray = reasons
          .mkString("[", ", ", "]")
          .replaceAll(AnsiColorPattern, "")
        s"""{"type": "buildError", "reasons": $reasonsArray}"""
      }
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
      case class Failure(reasons: List[Throwable], evalTime: Milliseconds)
          extends EvalResult[Nothing]
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
      val shouldSkip = dependencies.exists(!_.isInstanceOf[EvalResult.Value[_]])
      if (shouldSkip) EvalResult.Skipped
      else eval(task)
    }
  }

  class ProjectBuildFailureException(failedModules: Seq[String])
      extends Exception(
        s"${failedModules.size} module(s) finished with failures: ${failedModules.mkString(", ")}"
      ) {
    // Don't collect stack trace
    override def fillInStackTrace(): Throwable = this
  }

  object Utils {
    case class SemVersion(major: Int, minor: Int, patch: Int, preRelease: Option[String]) {
      def render = s"$major.$minor.$patch${preRelease.fold("")("-" + _)}"
    }
    object SemVersionExt {
      val SemVerPattern = raw"(\d+)\.(\d+)\.(\d+)(?:-(\w\d+))?.*".r
      def unapply(v: String) = v match {
        case SemVerPattern(major, minor, patch, preRelease) =>
          Some(SemVersion(major.toInt, minor.toInt, patch.toInt, Option(preRelease)))
        case _ => None
      }
    }

    // Some projects might define dual versionings for some of their projects,
    // eg. disneystreaming/weaver-test defines major.minor+1.patch for CE3 builds
    sealed trait DualVersioningType {
      import DualVersioningType._
      def matches(globalVersion: Version, currentVersion: Version): Boolean
      def apply(version: Version): Option[SemVersion]
    }
    object DualVersioningType {
      type Version = String
      def resolve = {
        val MinorPrefix = "minor:"
        sys.props.get("communitybuild.dualVersion") match {
          case Some(tpe) if tpe.startsWith(MinorPrefix) =>
            scala.util.Try(tpe.stripPrefix(MinorPrefix).toInt).map(DualMinor(_)).toOption
          case _ => None
        }
      }
      case class DualMinor(diff: Int) extends DualVersioningType {
        override def matches(globalVersion: String, currentVersion: String): Boolean = {
          (globalVersion, currentVersion) match {
            case (SemVersionExt(_), SemVersionExt(target)) =>
              apply(globalVersion).get == target
            case _ => false
          }
        }
        override def apply(version: Version): Option[SemVersion] = {
          version match {
            case SemVersionExt(ver) => Some(ver.copy(minor = ver.minor + diff))
            case _                  => None
          }
        }
      }
    }

    def filterTargets(targets: Seq[String], excludedPatterns: Seq[Regex]) = {
      targets.filter { target =>
        target.split("%") match {
          case Array(_, name) =>
            val excludingPattern = excludedPatterns.find { pattern =>
              // No Regex.matches in Scala 2.12 (!sic)
              pattern
                .findFirstIn(name)
                .orElse(pattern.findFirstIn(target))
                .filter(matched => matched == name || matched == target)
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

    private val multiStringSettings = Seq(
      "-scalajs-mapSourceURI",
      "-language",
      "-Wconf",
      "-Xmacro-settings",
      "-Yimports",
      "-Yfrom-tasty-ignore-list"
    )

    object logOnce extends Function[String, Unit] {
      val logged = collection.mutable.Set.empty[String]
      override def apply(v: String): Unit = if (logged.add(v)) println(s"OpenCB::$v")
    }
    // Invalid scalac options but not checked before 3.5.0-RC
    val invalidLanguageOptions = 0.until(5).map(v => s"3.$v") ++ Seq(
      "_",
      "experimental",
      "future",
      "scala3",
      "Scala3",
      "scala2",
      "Scala2",
      "scala2Compat",
      "Scala2Compat"
    )
    val ignoredScalacOptions = invalidLanguageOptions.map(v => s"-language:$v")
    def mapScalacOptions(
        current: Seq[String],
        append: Seq[String],
        remove: Seq[String]
    ): Seq[String] = {
      val (removeMatchSettings, removeSettings) = (remove.map(_.stripPrefix("REQUIRE:")) ++ ignoredScalacOptions).partition {
        _.startsWith("MATCH:")
      }
      val matchPatterns = removeMatchSettings.map(_.stripPrefix("MATCH:"))

      val (required, standardAppendSettings) = append.partition(_.startsWith("REQUIRE:"))
      val requiredAppendSettings = required.map(_.stripPrefix("REQUIRE:"))

      def isSourceVersion(v: String) = v.matches(raw"^-?-source(:(future|(\d\.\d+))(-migration)?)?")
      def resolveSourceVersion(v: String): Option[String] = v.split(":").drop(1).headOption
      val SourceVersionPattern = raw"^((3\.\d+|future)(-migration)?)$$".r
      val definedSourceSetting = current.find(isSourceVersion)
      lazy val definedSourceVersion: Option[String] =
        definedSourceSetting
          .flatMap(resolveSourceVersion) // -source:version
          .orElse(current.find(SourceVersionPattern.findFirstIn(_).isDefined)) // -source version
          .flatMap(SourceVersionPattern.findFirstMatchIn(_))
          .flatMap(m => Option(m.group(1)))
      lazy val requiredSourceVersion =
        requiredAppendSettings.find(isSourceVersion).flatMap(resolveSourceVersion)
      lazy val usesFutureSourceVersion =
        Seq("future", "future-migration").exists(definedSourceVersion.contains)

      val appendSettings = {
        def excludeIf(setting: String, expr: Boolean, reason: => String) = {
          if (expr) logOnce(s"Would not apply setting `$setting`: $reason")
          expr
        }

        val forcedSourceSettings: Seq[String] =
          requiredSourceVersion
            .map("-source:" + _)
            .orElse {
              if (usesFutureSourceVersion) Some("-source:future-migration")
              else None
            }
            .toSeq
        standardAppendSettings.filterNot { setting =>
          isSourceVersion(setting) && {
            excludeIf(
              setting,
              definedSourceVersion.nonEmpty,
              s"Project has predefined source version: ${definedSourceVersion.get}}"
            ) || excludeIf(
              setting,
              forcedSourceSettings.nonEmpty,
              s"Needs to use forced source version: $forcedSourceSettings instead of ${definedSourceVersion}"
            )
          }
        } ++ forcedSourceSettings
      }

      val normalizedExcludePatterns = (appendSettings ++ removeSettings).distinct.map { setting =>
        Seq[String => String](
          setting => if (setting.startsWith("--")) setting.tail else setting,
          setting => {
            setting.indexOf(':') match {
              case -1 => setting
              case n =>
                val name = setting.substring(0, n)
                def pattern = s"$name(:.*)?"
                if (multiStringSettings.contains(name)) setting // use original full setting
                else pattern
            }
          },
          setting => raw"^-?$setting"
        ).reduce(_.andThen(_))
          .apply(setting)
      }
      current
        .flatMap {
          case opt if opt.startsWith("-language:") =>
            opt.stripPrefix("-language:").split(',').map(v => s"-language:$v")
          case opt => List(opt)
        }
        .filterNot { s =>
          def isMatching(reason: String, found: Option[String]): Boolean = found match {
            case Some(matched) =>
              if (!appendSettings.contains(s))
                logOnce(s"Filter out '$s', $reason '$matched'")
              true
            case _ => false
          }
          isMatching("matches setting pattern", normalizedExcludePatterns.find(s.matches(_))) ||
          isMatching("matches regex", matchPatterns.find(s.matches(_))) ||
          definedSourceVersion.isEmpty && isMatching(
            "is dangling source version",
            SourceVersionPattern.findFirstIn(s.trim())
          )
        } ++ appendSettings.distinct
    }

    case class LibraryDependency(
        organization: String,
        artifact: String,
        version: String,
        crossScalaVersion: Boolean
    ) {
      override def toString(): String = {
        val crossVersion = if (crossScalaVersion) "%%" else "%"
        s"$organization $crossVersion $artifact % $version"
      }
    }

    private def escapeScalaVersion(scalaVersion: String)(str: String): String =
      str.replace("<SCALA_VERSION>", scalaVersion)
    final val ExtraLibraryDependenciesProp = "communitybuild.project.dependencies.add"
    def extraLibraryDependencies(scalaVersion: String): Seq[LibraryDependency] = sys.props
      .getOrElse(ExtraLibraryDependenciesProp, "")
      .split(';')
      .map(escapeScalaVersion(scalaVersion)(_))
      .filter(_.nonEmpty)
      .map(dep => dep.trim().split(':'))
      .flatMap {
        // org::artifact:version
        case Array(org, "", artifact, version) =>
          Some(LibraryDependency(org, artifact, version, crossScalaVersion = true))
        // org:artifact:version
        case Array(org, artifact, version) =>
          Some(LibraryDependency(org, artifact, version, crossScalaVersion = false))
        // other
        case segments =>
          logOnce(s"Invalid dependency format, segments=${segments.toList}")
          None
      }
      .toList
      .map { dep =>
        logOnce(s"Would include extra dependency: $dep")
        dep
      }
  }

}
