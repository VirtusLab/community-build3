package dashboard.core

import java.time.Instant
import io.circe.Codec

/** Summary of a single module's build tasks */
final case class ModuleSummary(
    module: String,
    compile: TaskResult,
    testCompile: TaskResult,
    test: TestResult,
    doc: TaskResult,
    publish: TaskResult
) derives Codec.AsObject

object ModuleSummary:
  extension (summary: ModuleSummary)
    def hasCompileFailure: Boolean =
      summary.compile.status == TaskStatus.Failed ||
        summary.testCompile.status == TaskStatus.Failed

    def hasTestFailure: Boolean =
      summary.test.status == TaskStatus.Failed

    def hasDocFailure: Boolean =
      summary.doc.status == TaskStatus.Failed

    def hasPublishFailure: Boolean =
      summary.publish.status == TaskStatus.Failed

/** Task execution status */
enum TaskStatus derives CanEqual:
  case Success, Failed, Skipped

object TaskStatus:
  def fromString(s: String): TaskStatus = s.toLowerCase match
    case "success" => Success
    case "failed"  => Failed
    case _         => Skipped

  given Codec[TaskStatus] = Codec.from(
    io.circe.Decoder.decodeString.map(fromString),
    io.circe.Encoder.encodeString.contramap(_.toString.toLowerCase)
  )

/** Result of a compilation/doc/publish task */
final case class TaskResult(
    status: TaskStatus,
    errors: Int = 0,
    warnings: Int = 0,
    tookMs: Long = 0,
    failureContext: Option[FailureContext] = None
) derives Codec.AsObject

/** Result of test execution */
final case class TestResult(
    status: TaskStatus,
    passed: Int = 0,
    failed: Int = 0,
    ignored: Int = 0,
    skipped: Int = 0,
    total: Long = 0,
    tookMs: Long = 0,
    failureContext: Option[FailureContext] = None
) derives Codec.AsObject

/** Context about why something failed */
final case class FailureContext(
    `type`: String,
    reasons: List[String] = Nil
) derives Codec.AsObject

/** Complete build result for a project */
final case class BuildResult(
    projectName: ProjectName,
    version: String,
    scalaVersion: String,
    buildId: String,
    buildTool: BuildTool,
    status: BuildStatus,
    buildURL: String,
    timestamp: Instant,
    summary: List[ModuleSummary],
    logs: Option[String] = None
) derives Codec.AsObject

object BuildResult:
  extension (result: BuildResult)
    def hasCompilerFailure: Boolean =
      result.summary.exists(_.hasCompileFailure)

    def hasTestFailure: Boolean =
      result.summary.exists(_.hasTestFailure)

    def hasDocFailure: Boolean =
      result.summary.exists(_.hasDocFailure)

    def hasPublishFailure: Boolean =
      result.summary.exists(_.hasPublishFailure)

    def isBuildFailure: Boolean =
      result.summary.isEmpty || result.status == BuildStatus.Started

    def failureReasons: List[FailureReason] =
      if result.isBuildFailure then List(FailureReason.Build)
      else
        val reasons = List(
          Option.when(result.hasCompilerFailure)(FailureReason.Compilation),
          Option.when(result.hasTestFailure)(FailureReason.Tests),
          Option.when(result.hasDocFailure)(FailureReason.Scaladoc),
          Option.when(result.hasPublishFailure)(FailureReason.Publish)
        ).flatten
        if reasons.isEmpty then List(FailureReason.Other) else reasons

/** Result of comparing two builds */
final case class ComparisonResult(
    baseScalaVersion: Option[String],
    baseBuildId: Option[String],
    targetScalaVersion: Option[String],
    targetBuildId: Option[String],
    newFailures: List[ProjectDiff],
    newFixes: List[ProjectDiff],
    stillFailing: List[ProjectDiff],
    stillPassing: Int
) derives Codec.AsObject

/** Diff information for a single project between two builds */
final case class ProjectDiff(
    projectName: ProjectName,
    version: String,
    baseStatus: Option[BuildStatus],
    targetStatus: BuildStatus,
    failureReasons: List[FailureReason],
    buildURL: String,
    buildId: String
) derives Codec.AsObject

/** Historical entry for a project */
final case class ProjectHistoryEntry(
    projectName: ProjectName,
    scalaVersion: String,
    version: String,
    status: BuildStatus,
    failureReasons: List[FailureReason],
    timestamp: Instant,
    buildURL: String,
    buildId: String
) derives Codec.AsObject

/** Project history with failure duration info */
final case class ProjectHistory(
    projectName: ProjectName,
    entries: List[ProjectHistoryEntry],
    currentlyFailing: Boolean,
    failingSince: Option[Instant],
    failingForDays: Option[Long]
) derives Codec.AsObject

/** Summary of a project's latest build status */
final case class ProjectSummary(
    projectName: ProjectName,
    latestScalaVersion: String,
    latestProjectVersion: String,
    status: BuildStatus,
    failureReasons: List[FailureReason],
    lastTested: Instant,
    buildId: String
) derives Codec.AsObject

/** List of all projects with stats */
final case class ProjectsList(
    projects: List[ProjectSummary],
    totalCount: Int,
    passingCount: Int,
    failingCount: Int
) derives Codec.AsObject

/** Build result enriched with failure streak information */
final case class BuildWithFailureStreak(
    build: BuildResult,
    failingSince: Option[Instant],
    failingForDays: Option[Long]
) derives Codec.AsObject

/** Failure streak info - how long failing and on which Scala version it started */
final case class FailureStreakInfo(
    days: Long,
    startedOnScalaVersion: String
) derives Codec.AsObject
