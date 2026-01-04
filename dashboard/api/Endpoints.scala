package dashboard.api

import sttp.tapir.*
import sttp.tapir.json.circe.*
import sttp.tapir.Schema
import io.circe.generic.auto.*
import java.time.Instant

import dashboard.core.*
import dashboard.data.{AllCacheStats, CacheStats, ClearAllResult, ProjectNote}

// Schema instances for custom types - must be defined before endpoint usage
object Schemas:
  // Basic types
  given Schema[ProjectName] = Schema.string
  given Schema[Instant] = Schema.string.format("date-time")

  // Enums
  given Schema[BuildStatus] = Schema.derivedEnumeration[BuildStatus].defaultStringBased
  given Schema[BuildTool] = Schema.derivedEnumeration[BuildTool].defaultStringBased
  given Schema[FailureReason] = Schema.derivedEnumeration[FailureReason].defaultStringBased
  given Schema[TaskStatus] = Schema.derivedEnumeration[TaskStatus].defaultStringBased
  given Schema[LogSeverity] = Schema.derivedEnumeration[LogSeverity].defaultStringBased

  // Complex types - derived
  given Schema[FailureContext] = Schema.derived
  given Schema[TaskResult] = Schema.derived
  given Schema[TestResult] = Schema.derived
  given Schema[ModuleSummary] = Schema.derived
  given Schema[BuildResult] = Schema.derived
  given Schema[ProjectDiff] = Schema.derived
  given Schema[ComparisonResult] = Schema.derived
  given Schema[ProjectHistoryEntry] = Schema.derived
  given Schema[ProjectHistory] = Schema.derived
  given Schema[LogEntry] = Schema.derived
  given Schema[ParsedLogs] = Schema.derived
  given Schema[FailureSignature] = Schema.derived
  given Schema[ProjectNote] = Schema.derived

  // Request types
  given Schema[AddNoteRequest] = Schema.derived
  given Schema[UpdateNoteRequest] = Schema.derived
  given Schema[CompareRequest] = Schema.derived

  // Cache types
  given Schema[CacheStats] = Schema.derived
  given Schema[AllCacheStats] = Schema.derived
  given Schema[ClearAllResult] = Schema.derived

  // Projects list types
  given Schema[ProjectSummary] = Schema.derived
  given Schema[ProjectsList] = Schema.derived

import Schemas.given

/** All API endpoint definitions */
object Endpoints:

  private val baseEndpoint = endpoint.in("api" / "v1")

  // ==================== Comparison Endpoints ====================

  val compare: PublicEndpoint[CompareRequest, String, ComparisonResult, Any] =
    baseEndpoint.get
      .in("compare")
      .in(query[Option[String]]("baseScalaVersion"))
      .in(query[Option[String]]("baseBuildId"))
      .in(query[Option[String]]("targetScalaVersion"))
      .in(query[Option[String]]("targetBuildId"))
      .mapIn(CompareRequest.apply.tupled)(r =>
        (r.baseScalaVersion, r.baseBuildId, r.targetScalaVersion, r.targetBuildId)
      )
      .out(jsonBody[ComparisonResult])
      .errorOut(stringBody)
      .description("Compare two builds by scala version or build ID")

  val listScalaVersions: PublicEndpoint[Unit, String, List[String], Any] =
    baseEndpoint.get
      .in("scala-versions")
      .out(jsonBody[List[String]])
      .errorOut(stringBody)
      .description("List all available Scala versions")

  val listBuildIds: PublicEndpoint[Option[String], String, List[String], Any] =
    baseEndpoint.get
      .in("build-ids")
      .in(query[Option[String]]("scalaVersion"))
      .out(jsonBody[List[String]])
      .errorOut(stringBody)
      .description("List build IDs, optionally filtered by Scala version")

  // ==================== History Endpoints ====================

  val projectHistory: PublicEndpoint[String, String, ProjectHistory, Any] =
    baseEndpoint.get
      .in("projects" / path[String]("projectName") / "history")
      .out(jsonBody[ProjectHistory])
      .errorOut(stringBody)
      .description("Get build history for a project")

  val buildDetails: PublicEndpoint[(String, String), String, BuildResult, Any] =
    baseEndpoint.get
      .in("projects" / path[String]("projectName") / "builds" / path[String]("buildId"))
      .out(jsonBody[BuildResult])
      .errorOut(stringBody)
      .description("Get detailed build result including logs")

  // ==================== Logs Endpoints ====================

  val logs: PublicEndpoint[(String, String, Option[String]), String, ParsedLogs, Any] =
    baseEndpoint.get
      .in("projects" / path[String]("projectName") / "builds" / path[String]("buildId") / "logs")
      .in(query[Option[String]]("severity").description("Filter by min severity: error, warning, or info"))
      .out(jsonBody[ParsedLogs])
      .errorOut(stringBody)
      .description("Get parsed logs with optional severity filtering")

  // ==================== Notes Endpoints ====================

  val getNotes: PublicEndpoint[String, String, List[ProjectNote], Any] =
    baseEndpoint.get
      .in("projects" / path[String]("projectName") / "notes")
      .out(jsonBody[List[ProjectNote]])
      .errorOut(stringBody)
      .description("Get all notes for a project")

  val addNote: PublicEndpoint[(String, AddNoteRequest), String, ProjectNote, Any] =
    baseEndpoint.post
      .in("projects" / path[String]("projectName") / "notes")
      .in(jsonBody[AddNoteRequest])
      .out(jsonBody[ProjectNote])
      .errorOut(stringBody)
      .description("Add a note to a project (requires authentication)")

  val updateNote: PublicEndpoint[(String, Long, UpdateNoteRequest), String, ProjectNote, Any] =
    baseEndpoint.put
      .in("projects" / path[String]("projectName") / "notes" / path[Long]("noteId"))
      .in(jsonBody[UpdateNoteRequest])
      .out(jsonBody[ProjectNote])
      .errorOut(stringBody)
      .description("Update a note (requires authentication)")

  val deleteNote: PublicEndpoint[(String, Long), String, Unit, Any] =
    baseEndpoint.delete
      .in("projects" / path[String]("projectName") / "notes" / path[Long]("noteId"))
      .out(emptyOutput)
      .errorOut(stringBody)
      .description("Delete a note (requires authentication)")

  // ==================== Failure Similarity Endpoints ====================

  val similarFailures: PublicEndpoint[(String, String), String, List[FailureSignature], Any] =
    baseEndpoint.get
      .in("projects" / path[String]("projectName") / "builds" / path[String]("buildId") / "similar")
      .out(jsonBody[List[FailureSignature]])
      .errorOut(stringBody)
      .description("Find other builds with similar failures")

  // ==================== Public/Latest Endpoints ====================

  val latestBuilds: PublicEndpoint[Unit, String, List[BuildResult], Any] =
    baseEndpoint.get
      .in("latest")
      .out(jsonBody[List[BuildResult]])
      .errorOut(stringBody)
      .description("Get latest build results (public endpoint)")

  val listProjects: PublicEndpoint[Unit, String, ProjectsList, Any] =
    baseEndpoint.get
      .in("projects")
      .out(jsonBody[ProjectsList])
      .errorOut(stringBody)
      .description("List all projects with their latest build status")

  // ==================== Admin Endpoints ====================

  private val adminEndpoint = endpoint.in("admin").tag("Admin")

  val cacheStats: PublicEndpoint[Unit, String, AllCacheStats, Any] =
    adminEndpoint.get
      .in("cache" / "stats")
      .out(jsonBody[AllCacheStats])
      .errorOut(stringBody)
      .description("Get statistics for all caches (requires authentication)")

  val clearCache: PublicEndpoint[Unit, String, ClearAllResult, Any] =
    adminEndpoint.post
      .in("cache" / "clear")
      .out(jsonBody[ClearAllResult])
      .errorOut(stringBody)
      .description("Clear all caches (requires authentication)")

  // All endpoints for documentation
  val all: List[AnyEndpoint] = List(
    compare,
    listScalaVersions,
    listBuildIds,
    projectHistory,
    buildDetails,
    logs,
    getNotes,
    addNote,
    updateNote,
    deleteNote,
    similarFailures,
    latestBuilds,
    listProjects,
    cacheStats,
    clearCache
  )

/** Request to compare two builds */
final case class CompareRequest(
    baseScalaVersion: Option[String],
    baseBuildId: Option[String],
    targetScalaVersion: Option[String],
    targetBuildId: Option[String]
)

/** Request to add a note */
final case class AddNoteRequest(
    scalaVersion: Option[String],
    buildId: Option[String],
    note: String,
    githubIssueUrl: Option[String]
)

/** Request to update a note */
final case class UpdateNoteRequest(
    note: String,
    githubIssueUrl: Option[String]
)
