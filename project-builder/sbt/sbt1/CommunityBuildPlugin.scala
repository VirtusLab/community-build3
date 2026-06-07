import sbt._
import sbt.protocol.testing.TestResult
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
        val output = value.asInstanceOf[sbt.Tests.Output]
        val status = output.overall match {
          case TestResult.Passed => Status.Ok
          case _                 => Status.Failed
        }
        def sum(results: Iterable[SuiteResult]) =
          results.foldLeft(TestStats.empty) { case (state, result) =>
            state.copy(
              passed = state.passed + result.passedCount,
              failed =
                state.failed + result.failureCount + result.errorCount + result.canceledCount,
              ignored = state.ignored + result.ignoredCount,
              skipped = state.skipped + result.skippedCount
            )
          }
        val byFrameworkStats: Map[String, TestStats] =
          (definedTests, loadedTestFrameworks) match {
            case (
                  EvalResult.Value(definedTests, _),
                  EvalResult.Value(loadedTestFrameworks, _)
                ) =>
              val frameworkByFingerprint = loadedTestFrameworks.values.flatMap { framework =>
                val name = framework.name()
                framework.fingerprints().map(_ -> name)
              }.toMap
              val testFrameworks = definedTests.map { test =>
                val framework = frameworkByFingerprint
                  .get(test.fingerprint)
                  .orElse {
                    frameworkByFingerprint.collectFirst {
                      case (fingerprint, name)
                          if test.fingerprint
                            .toString() == fingerprint.toString =>
                        name
                    }
                  }
                  .getOrElse("unknown")
                test.name -> framework
              }.toMap
              output.events
                .groupBy { case (testName, _) =>
                  testFrameworks.get(testName).getOrElse("unknown")
                }
                .map { case (frameworkName, results) =>
                  frameworkName -> sum(results.values)
                }
            case _ => Map.empty
          }
        val overallStats = sum(output.events.values)
        default.copy(
          status = status,
          overall = overallStats,
          byFramework = byFrameworkStats
        )
      case _ => default
    }
  }
}
