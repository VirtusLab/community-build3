package dashboard.api

import cats.effect.IO

import dashboard.core.*
import dashboard.data.ElasticsearchClient

/** Service for log exploration */
class LogsApi(esClient: ElasticsearchClient):

  /** Get parsed logs for a build with optional severity filtering */
  def getLogs(
      projectNameRaw: String,
      buildId: String,
      severityFilter: Option[String]
  ): IO[Either[String, ParsedLogs]] =
    ProjectName(projectNameRaw) match
      case Left(error)        => IO.pure(Left(error))
      case Right(projectName) =>
        val minSeverity = parseSeverity(severityFilter)
        esClient
          .getBuildDetails(projectName, buildId)
          .map:
            case Some(result) =>
              result.logs match
                case Some(logs) =>
                  val parsed = ParsedLogs.parse(logs)
                  val filtered = minSeverity.map(s => ParsedLogs.filterBySeverity(parsed, s)).getOrElse(parsed)
                  Right(filtered)
                case None =>
                  Left(s"No logs available for build: $projectNameRaw / $buildId")
            case None =>
              Left(s"Build not found: $projectNameRaw / $buildId")

  /** Extract error messages from logs */
  def extractErrors(projectNameRaw: String, buildId: String): IO[Either[String, List[String]]] =
    getLogs(projectNameRaw, buildId, Some("error")).map:
      case Right(parsed) => Right(parsed.entries.map(_.content))
      case Left(error)   => Left(error)

  /** Extract warnings from logs */
  def extractWarnings(projectNameRaw: String, buildId: String): IO[Either[String, List[String]]] =
    getLogs(projectNameRaw, buildId, Some("warning")).map:
      case Right(parsed) =>
        Right(parsed.entries.filter(_.severity == LogSeverity.Warning).map(_.content))
      case Left(error) => Left(error)

  /** Get log statistics */
  def getLogStats(projectNameRaw: String, buildId: String): IO[Either[String, LogStats]] =
    getLogs(projectNameRaw, buildId, None).map:
      case Right(parsed) =>
        Right(
          LogStats(
            totalLines = parsed.entries.length,
            errorCount = parsed.errorCount,
            warningCount = parsed.warningCount,
            infoCount = parsed.infoCount
          )
        )
      case Left(error) => Left(error)

  private def parseSeverity(filter: Option[String]): Option[LogSeverity] =
    filter
      .map(_.toLowerCase)
      .flatMap:
        case "error"   => Some(LogSeverity.Error)
        case "warning" => Some(LogSeverity.Warning)
        case "info"    => Some(LogSeverity.Info)
        case _         => None

/** Log statistics summary */
final case class LogStats(
    totalLines: Int,
    errorCount: Int,
    warningCount: Int,
    infoCount: Int
)
