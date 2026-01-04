package dashboard.api

import cats.effect.IO
import java.time.{Instant, Duration}

import dashboard.core.*
import dashboard.data.ElasticsearchClient

/** Service for project history queries */
class HistoryApi(esClient: ElasticsearchClient):

  /** Get full history for a project with failure duration info */
  def getHistory(projectNameRaw: String): IO[Either[String, ProjectHistory]] =
    ProjectName(projectNameRaw) match
      case Left(error)        => IO.pure(Left(error))
      case Right(projectName) =>
        esClient
          .getProjectHistory(projectName)
          .map: entries =>
            if entries.isEmpty then Left(s"No history found for project: $projectNameRaw")
            else Right(computeProjectHistory(projectName, entries))

  /** Get details of a specific build */
  def getBuildDetails(projectNameRaw: String, buildId: String): IO[Either[String, BuildResult]] =
    ProjectName(projectNameRaw) match
      case Left(error)        => IO.pure(Left(error))
      case Right(projectName) =>
        esClient
          .getBuildDetails(projectName, buildId)
          .map:
            case Some(result) => Right(result)
            case None         => Left(s"Build not found: $projectNameRaw / $buildId")

  private def computeProjectHistory(
      projectName: ProjectName,
      entries: List[ProjectHistoryEntry]
  ): ProjectHistory =
    // Sort by timestamp descending (most recent first)
    val sorted = entries.sortBy(_.timestamp)(using Ordering[Instant].reverse)

    // Determine current failure status
    val latestEntry = sorted.headOption
    val currentlyFailing = latestEntry.exists(_.status == BuildStatus.Failure)

    // Find when the current failure streak started
    val failingSince = if currentlyFailing then findFailureStart(sorted) else None

    // Calculate how long it's been failing
    val failingForDays = failingSince.map: start =>
      Duration.between(start, Instant.now()).toDays()

    ProjectHistory(
      projectName = projectName,
      entries = sorted,
      currentlyFailing = currentlyFailing,
      failingSince = failingSince,
      failingForDays = failingForDays
    )

  /** Find the timestamp when the current failure streak started */
  private def findFailureStart(sortedEntries: List[ProjectHistoryEntry]): Option[Instant] =
    // Walk backwards from most recent, find where failures started
    val failingEntries = sortedEntries.takeWhile(_.status == BuildStatus.Failure)
    failingEntries.lastOption.map(_.timestamp)

  /** Group history by Scala version for timeline display */
  def groupByScalaVersion(history: ProjectHistory): Map[String, List[ProjectHistoryEntry]] =
    history.entries.groupBy(_.scalaVersion)

  /** Find status transitions (when project went from passing to failing or vice versa) */
  def findStatusTransitions(history: ProjectHistory): List[StatusTransition] =
    val sorted = history.entries.sortBy(_.timestamp)
    sorted
      .zip(sorted.drop(1))
      .flatMap: (prev, curr) =>
        if prev.status != curr.status then
          Some(
            StatusTransition(
              fromStatus = prev.status,
              toStatus = curr.status,
              timestamp = curr.timestamp,
              scalaVersion = curr.scalaVersion,
              buildId = curr.buildId
            )
          )
        else None

/** Represents a status change in project history */
final case class StatusTransition(
    fromStatus: BuildStatus,
    toStatus: BuildStatus,
    timestamp: Instant,
    scalaVersion: String,
    buildId: String
)
