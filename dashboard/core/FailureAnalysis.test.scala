package dashboard.core

import munit.FunSuite

class FailureSignatureTest extends FunSuite:

  test("extract errors from logs"):
    val logs = """
      |[info] Compiling 10 files
      |[error] /path/to/File.scala:10:5: error: type mismatch
      |[error]   found: String
      |[error]   required: Int
      |[warn] deprecated API usage
      |[info] Done
    """.stripMargin

    val sig = FailureSignature.fromLogs(
      ProjectName.unsafeApply("test/project"),
      "build-123",
      logs
    )

    assert(sig.normalizedErrors.nonEmpty)
    assert(sig.errorHash.nonEmpty)

  test("normalize errors for comparison"):
    val logs1 = """
      |[error] /home/user/project/src/main/scala/Foo.scala:42:10: error: type mismatch
    """.stripMargin

    val logs2 = """
      |[error] /different/path/src/main/scala/Foo.scala:100:5: error: type mismatch
    """.stripMargin

    val sig1 = FailureSignature.fromLogs(
      ProjectName.unsafeApply("test/project"),
      "build-1",
      logs1
    )

    val sig2 = FailureSignature.fromLogs(
      ProjectName.unsafeApply("test/project"),
      "build-2",
      logs2
    )

    // Same error type should have high similarity despite different paths/lines
    assert(sig1.similarityScore(sig2) > 0.5)

  test("identical logs produce identical hashes"):
    val logs = "[error] Something went wrong"

    val sig1 = FailureSignature.fromLogs(
      ProjectName.unsafeApply("test/project"),
      "build-1",
      logs
    )

    val sig2 = FailureSignature.fromLogs(
      ProjectName.unsafeApply("test/project"),
      "build-2",
      logs
    )

    assertEquals(sig1.errorHash, sig2.errorHash)
    assert(sig1.isSimilarTo(sig2))

class ParsedLogsTest extends FunSuite:

  test("categorize log entries by severity"):
    val logs = """
      |[info] Starting compilation
      |[warn] Deprecated API usage
      |[error] Compilation failed
      |error: type mismatch
    """.stripMargin

    val parsed = ParsedLogs.parse(logs)

    assertEquals(parsed.errorCount, 2)
    assertEquals(parsed.warningCount, 1)
    assert(parsed.infoCount >= 1)

  test("filter by severity"):
    val logs = """
      |[info] Info message
      |[warn] Warning message
      |[error] Error message
    """.stripMargin

    val parsed = ParsedLogs.parse(logs)
    val errorsOnly = ParsedLogs.filterBySeverity(parsed, LogSeverity.Error)
    val warningsAndErrors = ParsedLogs.filterBySeverity(parsed, LogSeverity.Warning)

    assertEquals(errorsOnly.entries.length, 1)
    assertEquals(warningsAndErrors.entries.length, 2)
