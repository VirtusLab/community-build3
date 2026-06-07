import sbt._
import sbt.virtuslab.ocb.CommunityBuildTestSupport
import Scala3CommunityBuild._
import Scala3CommunityBuild.TaskEvaluator.EvalResult

object CommunityBuildPlugin extends AutoPlugin with CommunityBuildPluginShared {
  override protected def collectTestResults(
      evalResult: EvalResult[?],
      definedTests: EvalResult[Seq[sbt.TestDefinition]],
      loadedTestFrameworks: EvalResult[
        Map[sbt.TestFramework, sbt.testing.Framework]
      ]
  ): TestsResult = {
    val default = TestsResult(
      evalResult.toStatus,
      failureContext = evalResult.toBuildError,
      tookMs = evalResult.evalTime
    )

    evalResult match {
      case EvalResult.Value(value, _) =>
        val (definedTestsSeq, loadedFrameworksMap) =
          (definedTests, loadedTestFrameworks) match {
            case (
                  EvalResult.Value(definedTests, _),
                  EvalResult.Value(loadedTestFrameworks, _)
                ) =>
              (definedTests, loadedTestFrameworks)
            case _ => (Seq.empty, Map.empty)
          }
        val collected = CommunityBuildTestSupport.collectFromTaskValue(
          value,
          definedTestsSeq,
          loadedFrameworksMap
        )
        def toCoreStats(stats: CommunityBuildTestSupport.TestStats): TestStats =
          TestStats(
            passed = stats.passed,
            failed = stats.failed,
            ignored = stats.ignored,
            skipped = stats.skipped
          )
        default.copy(
          status = if (collected.passed) Status.Ok else Status.Failed,
          overall = toCoreStats(collected.overall),
          byFramework = collected.byFramework.view.mapValues(toCoreStats).toMap
        )
      case _ => default
    }
  }
}
