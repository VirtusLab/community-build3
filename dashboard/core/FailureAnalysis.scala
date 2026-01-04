package dashboard.core

import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import io.circe.Codec

/** Signature of a failure for similarity detection */
final case class FailureSignature(
    projectName: ProjectName,
    buildId: String,
    errorHash: String,
    errorSummary: String,
    normalizedErrors: List[String]
) derives Codec.AsObject

object FailureSignature:
  /** Maximum length of error summary for display */
  private val SummaryMaxLength = 200

  /** Create a failure signature from build logs */
  def fromLogs(
      projectName: ProjectName,
      buildId: String,
      logs: String
  ): FailureSignature =
    val errors = extractErrors(logs)
    val normalized = errors.map(normalizeError)
    val hash = computeHash(normalized)
    val summary = errors.headOption
      .map(_.take(SummaryMaxLength))
      .getOrElse("")

    FailureSignature(
      projectName = projectName,
      buildId = buildId,
      errorHash = hash,
      errorSummary = summary,
      normalizedErrors = normalized
    )

  /** Extract error lines from logs */
  private def extractErrors(logs: String): List[String] =
    val errorPatterns = List(
      raw"^\[error\].*".r,
      raw"^error:.*".r,
      raw"^Error:.*".r,
      raw".*\.scala:\d+:\d+: error:.*".r
    )
    logs.linesIterator
      .filter(line => errorPatterns.exists(_.findFirstIn(line).isDefined))
      .toList
      .distinct
      .take(50) // Limit to prevent huge signatures

  /** Normalize an error message for comparison */
  private def normalizeError(error: String): String =
    error
      // Remove file paths but keep filename
      .replaceAll(raw"(/[^/\s]+)+/([^/\s]+\.scala)", "$2")
      // Remove line/column numbers
      .replaceAll(raw":\d+:\d+:", ":<line>:<col>:")
      .replaceAll(raw":\d+:", ":<line>:")
      // Remove memory addresses
      .replaceAll(raw"@[0-9a-f]+", "@<addr>")
      // Remove timestamps
      .replaceAll(raw"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}", "<timestamp>")
      // Normalize whitespace
      .replaceAll(raw"\s+", " ")
      .trim
      .toLowerCase

  /** Compute hash of normalized errors */
  private def computeHash(errors: List[String]): String =
    val digest = MessageDigest.getInstance("SHA-256")
    val content = errors.sorted.mkString("\n")
    digest
      .digest(content.getBytes(StandardCharsets.UTF_8))
      .map("%02x".format(_))
      .mkString
      .take(16) // Short hash is sufficient

  extension (sig: FailureSignature)
    /** Check if this failure is similar to another */
    def isSimilarTo(other: FailureSignature): Boolean =
      sig.errorHash == other.errorHash

    /** Compute similarity score (0.0 to 1.0) with another signature */
    def similarityScore(other: FailureSignature): Double =
      if sig.errorHash == other.errorHash then 1.0
      else
        val common = sig.normalizedErrors.toSet.intersect(other.normalizedErrors.toSet)
        val total = sig.normalizedErrors.toSet.union(other.normalizedErrors.toSet)
        if total.isEmpty then 0.0
        else common.size.toDouble / total.size.toDouble

/** Log entry with severity level */
final case class LogEntry(
    line: Int,
    content: String,
    severity: LogSeverity
) derives Codec.AsObject

/** Log severity levels */
enum LogSeverity derives CanEqual:
  case Error, Warning, Info

object LogSeverity:
  given Codec[LogSeverity] = Codec.from(
    io.circe.Decoder.decodeString.map(s => LogSeverity.valueOf(s.capitalize)),
    io.circe.Encoder.encodeString.contramap(_.toString.toLowerCase)
  )

/** Parsed and categorized logs */
final case class ParsedLogs(
    entries: List[LogEntry],
    errorCount: Int,
    warningCount: Int,
    infoCount: Int
) derives Codec.AsObject

object ParsedLogs:
  /** Parse raw log text into categorized entries */
  def parse(logs: String): ParsedLogs =
    val entries = logs.linesIterator.zipWithIndex.map { (line, idx) =>
      val severity = detectSeverity(line)
      LogEntry(idx + 1, line, severity)
    }.toList

    ParsedLogs(
      entries = entries,
      errorCount = entries.count(_.severity == LogSeverity.Error),
      warningCount = entries.count(_.severity == LogSeverity.Warning),
      infoCount = entries.count(_.severity == LogSeverity.Info)
    )

  /** Filter logs by minimum severity */
  def filterBySeverity(logs: ParsedLogs, minSeverity: LogSeverity): ParsedLogs =
    val allowedSeverities = minSeverity match
      case LogSeverity.Error   => Set(LogSeverity.Error)
      case LogSeverity.Warning => Set(LogSeverity.Error, LogSeverity.Warning)
      case LogSeverity.Info    => Set(LogSeverity.Error, LogSeverity.Warning, LogSeverity.Info)

    val filtered = logs.entries.filter(e => allowedSeverities.contains(e.severity))
    logs.copy(entries = filtered)

  private def detectSeverity(line: String): LogSeverity =
    val lower = line.toLowerCase
    if lower.contains("[error]") || lower.contains("error:") || lower.contains("exception") then LogSeverity.Error
    else if lower.contains("[warn]") || lower.contains("warning:") then LogSeverity.Warning
    else LogSeverity.Info
