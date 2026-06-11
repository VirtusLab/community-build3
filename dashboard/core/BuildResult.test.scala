package dashboard.core

import java.time.Instant
import munit.FunSuite

class BuildResultTest extends FunSuite:

  private val project = ProjectName.unsafeApply("ablearthy/td-types")
  private val older = Instant.parse("2024-06-08T10:00:00Z")
  private val newer = Instant.parse("2024-06-09T10:00:00Z")

  private def build(
      buildId: String,
      timestamp: Instant,
      status: BuildStatus = BuildStatus.Failure
  ): BuildResult =
    BuildResult(
      projectName = project,
      version = "1.8.39",
      scalaVersion = "3.9.0-RC1",
      buildId = buildId,
      buildTool = BuildTool.Sbt,
      status = status,
      buildURL = "https://example.com",
      timestamp = timestamp,
      summary = Nil
    )

  test("latestPerProjectAndBuild keeps newest duplicate for same project and buildId"):
    val olderBuild = build("3.9.0-RC1:2024-06-08", older)
    val newerBuild = build("3.9.0-RC1:2024-06-08", newer, BuildStatus.Success)
    val result = BuildResult.latestPerProjectAndBuild(List(olderBuild, newerBuild))

    assertEquals(result.length, 1)
    assertEquals(result.head.timestamp, newer)
    assertEquals(result.head.status, BuildStatus.Success)

  test("latestPerProjectAndBuild keeps separate entries for different buildIds"):
    val buildA = build("3.9.0-RC1:2024-06-08", older)
    val buildB = build("3.9.0-RC1:2024-06-09", newer)
    val result = BuildResult.latestPerProjectAndBuild(List(buildA, buildB))

    assertEquals(result.length, 2)
    assertEquals(result.map(_.buildId).toSet, Set("3.9.0-RC1:2024-06-08", "3.9.0-RC1:2024-06-09"))
