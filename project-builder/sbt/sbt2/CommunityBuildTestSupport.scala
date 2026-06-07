package sbt.virtuslab.ocb

import sbt._
import sbt.protocol.testing.TestResult

/** Reads package-private `Tests.Output` from a nested `sbt` package. */
object CommunityBuildTestSupport {
  final case class TestStats(passed: Int, failed: Int, ignored: Int, skipped: Int)
  object TestStats {
    val empty: TestStats = TestStats(0, 0, 0, 0)
  }

  final case class CollectedTestResults(
      passed: Boolean,
      overall: TestStats,
      byFramework: Map[String, TestStats]
  )

  def collectFromTaskValue(
      value: Any,
      definedTests: Seq[TestDefinition],
      loadedTestFrameworks: Map[TestFramework, testing.Framework]
  ): CollectedTestResults =
    collectFromOutput(
      value.asInstanceOf[Tests.Output],
      definedTests,
      loadedTestFrameworks
    )

  def collectFromOutput(
      output: Tests.Output,
      definedTests: Seq[TestDefinition],
      loadedTestFrameworks: Map[TestFramework, testing.Framework]
  ): CollectedTestResults = {
    def sum(results: Iterable[SuiteResult]): TestStats =
      results.foldLeft(TestStats.empty) { case (state, result) =>
        state.copy(
          passed = state.passed + result.passedCount,
          failed =
            state.failed + result.failureCount + result.errorCount + result.canceledCount,
          ignored = state.ignored + result.ignoredCount,
          skipped = state.skipped + result.skippedCount
        )
      }

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
                if test.fingerprint.toString() == fingerprint.toString() =>
              name
          }
        }
        .getOrElse("unknown")
      test.name -> framework
    }.toMap

    val byFrameworkStats = output.events
      .groupBy { case (testName, _) =>
        testFrameworks.get(testName).getOrElse("unknown")
      }
      .map { case (frameworkName, results) =>
        frameworkName -> sum(results.values)
      }

    CollectedTestResults(
      passed = output.overall == TestResult.Passed,
      overall = sum(output.events.values),
      byFramework = byFrameworkStats
    )
  }
}
