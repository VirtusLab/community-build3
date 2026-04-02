#!/usr/bin/env -S scala-cli
//> using scala "3"
//> using dep "com.lihaoyi::upickle:3.0.0"
//> using options -Wunused:all -deprecation

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

import scala.util.Try

object ReleaseAgeScalaMinorDistributionApp:
  private val Utc = ZoneOffset.UTC

  private val DefaultBuildConfigPath = Path.of(".github/workflows/buildConfig.json")
  private val DefaultReleaseDatesPath = Path.of("out/project-release-dates.json")

  enum OutputFormat:
    case Table, Json

  final case class CliConfig(
      buildConfigPath: Path = DefaultBuildConfigPath,
      releaseDatesPath: Path = DefaultReleaseDatesPath,
      asOf: LocalDate = LocalDate.now(Utc),
      format: OutputFormat = OutputFormat.Table
  )

  final case class BuildProject(
      name: String,
      publishedScalaVersion: Option[String]
  )

  final case class ReleaseRecord(
      rawDate: Option[String],
      releaseDate: Option[LocalDate]
  )

  final case class Bucket(
      id: String,
      label: String,
      description: String,
      startInclusive: Option[LocalDate],
      endInclusive: Option[LocalDate]
  ):
    def contains(date: LocalDate): Boolean =
      val afterStart = startInclusive.forall(start => !date.isBefore(start))
      val beforeEnd = endInclusive.forall(end => !date.isAfter(end))
      afterStart && beforeEnd

  final case class BucketStats(
      bucket: Bucket,
      total: Int,
      countsByMinor: Map[String, Int]
  )

  final case class Summary(
      projectsInBuildConfig: Int,
      projectsWithBucket: Int,
      missingReleaseEntry: Int,
      missingReleaseDate: Int,
      invalidReleaseDate: Int,
      invalidPublishedScalaVersion: Int,
      releaseAfterAsOf: Int
  )

  final case class Report(
      config: CliConfig,
      scalaMinors: List[String],
      summary: Summary,
      buckets: List[BucketStats]
  )

  def run(args: List[String]): Unit =
    if args.contains("--help") || args.contains("-h") then
      println(usage)
    else
      val config = parseArgs(args.toList)
      ensureReadable(config.buildConfigPath)
      ensureReadable(config.releaseDatesPath)

      val buildProjects = loadBuildProjects(config.buildConfigPath)
      val releaseRecords = loadReleaseRecords(config.releaseDatesPath)
      val report = aggregate(buildProjects, releaseRecords, config)

      config.format match
        case OutputFormat.Table => println(renderTableReport(report))
        case OutputFormat.Json  => println(renderJsonReport(report))

  private def parseArgs(args: List[String]): CliConfig =
    args.foldLeft(CliConfig()) {
      case (config, s"--build-config=$path") =>
        config.copy(buildConfigPath = Path.of(path))
      case (config, s"--release-dates=$path") =>
        config.copy(releaseDatesPath = Path.of(path))
      case (config, s"--as-of=$date") =>
        config.copy(asOf = parseDateArg(date))
      case (config, "--format=table") =>
        config.copy(format = OutputFormat.Table)
      case (config, "--format=json") =>
        config.copy(format = OutputFormat.Json)
      case (_, unknown) =>
        throw IllegalArgumentException(s"Unknown argument: $unknown\n\n$usage")
    }

  private def parseDateArg(value: String): LocalDate =
    Try(LocalDate.parse(value)).getOrElse {
      throw IllegalArgumentException(s"--as-of must use YYYY-MM-DD, got: $value")
    }

  private def usage: String =
    """Usage: scala-cli run scripts/release-age-scala-minor-distribution.scala -- [options]
      |
      |Options:
      |  --build-config=<path>    Path to buildConfig.json
      |  --release-dates=<path>   Path to project-release-dates.json
      |  --as-of=<YYYY-MM-DD>     Reference date for bucket boundaries, defaults to current UTC date
      |  --format=table|json      Output format, defaults to table
      |
      |Buckets are non-overlapping:
      |  - last 3 months
      |  - last year, excluding last 3 months
      |  - last 3 years, excluding last year
      |  - last 5 years, excluding last 3 years
      |  - older than 5 years
      |""".stripMargin

  private def ensureReadable(path: Path): Unit =
    if !Files.isRegularFile(path) then
      throw IllegalArgumentException(s"File not found: $path")

  private def loadBuildProjects(path: Path): List[BuildProject] =
    val json = ujson.read(Files.readString(path))
    json.obj.toList.map { case (projectName, value) =>
      val obj = value.obj
      BuildProject(
        name = projectName,
        publishedScalaVersion = obj.value
          .get("publishedScalaVersion")
          .collect { case ujson.Str(version) if version.trim.nonEmpty => version.trim }
      )
    }

  private def loadReleaseRecords(path: Path): Map[String, ReleaseRecord] =
    val json = ujson.read(Files.readString(path))
    json.obj.toMap.map { case (projectName, value) =>
      projectName -> parseReleaseRecord(value)
    }

  private def parseReleaseRecord(value: ujson.Value): ReleaseRecord =
    val rawDate =
      value.obj.value.get("date").collect { case ujson.Str(date) if date.trim.nonEmpty => date.trim }
    val releaseDate = rawDate.flatMap(parseInstantToUtcDate)
    ReleaseRecord(rawDate = rawDate, releaseDate = releaseDate)

  private def parseInstantToUtcDate(value: String): Option[LocalDate] =
    Try(Instant.parse(value).atZone(Utc).toLocalDate).toOption

  private def aggregate(
      buildProjects: List[BuildProject],
      releaseRecords: Map[String, ReleaseRecord],
      config: CliConfig
  ): Report =
    val buckets = buildBuckets(config.asOf)

    final case class ProjectBucket(bucketId: String, scalaMinor: String)

    val (projectBuckets, summary) =
      buildProjects.foldLeft((Vector.empty[ProjectBucket], Summary(buildProjects.size, 0, 0, 0, 0, 0, 0))) {
        case ((acc, summary), project) =>
          releaseRecords.get(project.name) match
            case None =>
              (acc, summary.copy(missingReleaseEntry = summary.missingReleaseEntry + 1))
            case Some(record) if record.rawDate.isEmpty =>
              (acc, summary.copy(missingReleaseDate = summary.missingReleaseDate + 1))
            case Some(record) if record.releaseDate.isEmpty =>
              (acc, summary.copy(invalidReleaseDate = summary.invalidReleaseDate + 1))
            case Some(record) =>
              val releaseDate = record.releaseDate.getOrElse(sys.error("unreachable"))
              if releaseDate.isAfter(config.asOf) then
                (acc, summary.copy(releaseAfterAsOf = summary.releaseAfterAsOf + 1))
              else
                val bucket = buckets.find(_.contains(releaseDate)).getOrElse {
                  throw IllegalStateException(s"No bucket found for release date $releaseDate")
                }
                toScalaMinor(project.publishedScalaVersion) match
                  case None =>
                    (
                      acc,
                      summary.copy(
                        invalidPublishedScalaVersion = summary.invalidPublishedScalaVersion + 1
                      )
                    )
                  case Some(scalaMinor) =>
                    (
                      acc :+ ProjectBucket(bucket.id, scalaMinor),
                      summary.copy(projectsWithBucket = summary.projectsWithBucket + 1)
                    )
      }

    val scalaMinors = projectBuckets.map(_.scalaMinor).distinct.sorted(using scalaMinorOrdering).toList

    val bucketStats =
      buckets.map { bucket =>
        val projectsInBucket = projectBuckets.filter(_.bucketId == bucket.id)
        BucketStats(
          bucket = bucket,
          total = projectsInBucket.size,
          countsByMinor = projectsInBucket.groupMapReduce(_.scalaMinor)(_ => 1)(_ + _)
        )
      }

    Report(
      config = config,
      scalaMinors = scalaMinors,
      summary = summary,
      buckets = bucketStats
    )

  private def buildBuckets(asOf: LocalDate): List[Bucket] =
    val threeMonthsAgo = asOf.minusMonths(3)
    val oneYearAgo = asOf.minusYears(1)
    val threeYearsAgo = asOf.minusYears(3)
    val fiveYearsAgo = asOf.minusYears(5)

    List(
      Bucket(
        id = "last-3-months",
        label = "Last 3 months",
        description = s"Released from $threeMonthsAgo through $asOf",
        startInclusive = Some(threeMonthsAgo),
        endInclusive = Some(asOf)
      ),
      Bucket(
        id = "last-year-excluding-3-months",
        label = "Last year, older than 3 months",
        description = s"Released from $oneYearAgo through ${threeMonthsAgo.minusDays(1)}",
        startInclusive = Some(oneYearAgo),
        endInclusive = Some(threeMonthsAgo.minusDays(1))
      ),
      Bucket(
        id = "last-3-years-excluding-1-year",
        label = "Last 3 years, older than 1 year",
        description = s"Released from $threeYearsAgo through ${oneYearAgo.minusDays(1)}",
        startInclusive = Some(threeYearsAgo),
        endInclusive = Some(oneYearAgo.minusDays(1))
      ),
      Bucket(
        id = "last-5-years-excluding-3-years",
        label = "Last 5 years, older than 3 years",
        description = s"Released from $fiveYearsAgo through ${threeYearsAgo.minusDays(1)}",
        startInclusive = Some(fiveYearsAgo),
        endInclusive = Some(threeYearsAgo.minusDays(1))
      ),
      Bucket(
        id = "older-than-5-years",
        label = "Older than 5 years",
        description = s"Released on or before ${fiveYearsAgo.minusDays(1)}",
        startInclusive = None,
        endInclusive = Some(fiveYearsAgo.minusDays(1))
      )
    )

  private def toScalaMinor(publishedScalaVersion: Option[String]): Option[String] =
    publishedScalaVersion.map(_.trim).filter(_.nonEmpty) match
      case Some(version) =>
        val parts = version.split("[.-]").toList
        parts match
          case major :: minor :: patch :: _ if isValidScala3PublishedVersion(major, minor, patch) =>
            Some(s"$major.$minor")
          case major :: minor :: patch :: Nil if isValidScala3PublishedVersion(major, minor, patch) =>
            Some(s"$major.$minor")
          case _ =>
            None
      case None =>
        Some("unknown")

  private def isValidScala3PublishedVersion(major: String, minor: String, patch: String): Boolean =
    major.forall(_.isDigit) &&
    minor.forall(_.isDigit) &&
    patch.forall(_.isDigit) &&
    major.toInt >= 3

  private val scalaMinorOrdering: Ordering[String] = Ordering.by { version =>
    version.split('.').toList match
      case major :: minor :: Nil if major.forall(_.isDigit) && minor.forall(_.isDigit) =>
        (0, major.toInt, minor.toInt, "")
      case _ =>
        (1, Int.MaxValue, Int.MaxValue, version)
  }

  private def renderTableReport(report: Report): String =
    val countHeaders = "Bucket" :: "Range" :: "Total" :: report.scalaMinors
    val countRows = report.buckets.map { bucketStats =>
      val counts = report.scalaMinors.map(minor => bucketStats.countsByMinor.getOrElse(minor, 0).toString)
      List(bucketStats.bucket.label, bucketStats.bucket.description, bucketStats.total.toString) ++ counts
    }

    val percentHeaders = "Bucket" :: "Total" :: report.scalaMinors
    val percentRows = report.buckets.map { bucketStats =>
      val shares = report.scalaMinors.map { minor =>
        formatPercentage(bucketStats.countsByMinor.getOrElse(minor, 0), bucketStats.total)
      }
      List(bucketStats.bucket.label, bucketStats.total.toString) ++ shares
    }

    List(
      s"As of ${report.config.asOf} (UTC)",
      s"Build config: ${report.config.buildConfigPath}",
      s"Release dates: ${report.config.releaseDatesPath}",
      "",
      "Summary",
      s"  Projects in build config: ${report.summary.projectsInBuildConfig}",
      s"  Projects included in buckets: ${report.summary.projectsWithBucket}",
      s"  Missing release entry: ${report.summary.missingReleaseEntry}",
      s"  Missing/null release date: ${report.summary.missingReleaseDate}",
      s"  Invalid release date: ${report.summary.invalidReleaseDate}",
      s"  Invalid published Scala version (< 3.0.0 or malformed): ${report.summary.invalidPublishedScalaVersion}",
      s"  Release date after as-of: ${report.summary.releaseAfterAsOf}",
      "",
      "Counts by release-age bucket and Scala minor",
      renderTable(countHeaders, countRows),
      "",
      "Within-bucket distribution (%)",
      renderTable(percentHeaders, percentRows)
    ).mkString("\n")

  private def renderJsonReport(report: Report): String =
    def dateValue(value: Option[LocalDate]): ujson.Value =
      value.map(date => ujson.Str(date.toString)).getOrElse(ujson.Null)

    val bucketsJson = ujson.Arr.from(report.buckets.map { bucketStats =>
      val countsByMinor = ujson.Obj.from(report.scalaMinors.map { minor =>
        minor -> ujson.Num(bucketStats.countsByMinor.getOrElse(minor, 0))
      })
      val sharesByMinor = ujson.Obj.from(report.scalaMinors.map { minor =>
        minor -> ujson.Num(share(bucketStats.countsByMinor.getOrElse(minor, 0), bucketStats.total))
      })

      ujson.Obj(
        "id" -> bucketStats.bucket.id,
        "label" -> bucketStats.bucket.label,
        "description" -> bucketStats.bucket.description,
        "startInclusive" -> dateValue(bucketStats.bucket.startInclusive),
        "endInclusive" -> dateValue(bucketStats.bucket.endInclusive),
        "total" -> bucketStats.total,
        "countsByScalaMinor" -> countsByMinor,
        "shareByScalaMinor" -> sharesByMinor
      )
    })

    val json =
      ujson.Obj(
        "asOf" -> report.config.asOf.toString,
        "buildConfigPath" -> report.config.buildConfigPath.toString,
        "releaseDatesPath" -> report.config.releaseDatesPath.toString,
        "scalaMinors" -> ujson.Arr.from(report.scalaMinors.map(ujson.Str(_))),
        "summary" -> ujson.Obj(
          "projectsInBuildConfig" -> report.summary.projectsInBuildConfig,
          "projectsWithBucket" -> report.summary.projectsWithBucket,
          "missingReleaseEntry" -> report.summary.missingReleaseEntry,
          "missingReleaseDate" -> report.summary.missingReleaseDate,
          "invalidReleaseDate" -> report.summary.invalidReleaseDate,
          "invalidPublishedScalaVersion" -> report.summary.invalidPublishedScalaVersion,
          "releaseAfterAsOf" -> report.summary.releaseAfterAsOf
        ),
        "buckets" -> bucketsJson
      )

    ujson.write(json, indent = 2)

  private def share(count: Int, total: Int): Double =
    if total == 0 then 0.0 else count.toDouble / total.toDouble

  private def formatPercentage(count: Int, total: Int): String =
    if total == 0 then "0.0%"
    else f"${share(count, total) * 100.0}%.1f%%"

  private def renderTable(headers: List[String], rows: List[List[String]]): String =
    val allRows = headers :: rows
    val widths =
      headers.indices.map { idx =>
        allRows.iterator.map(_.lift(idx).getOrElse("")).map(_.length).max
      }.toList

    def renderRow(row: List[String]): String =
      row.zipWithIndex
        .map { case (value, idx) => value.padTo(widths(idx), ' ') }
        .mkString("  ")

    val separator = widths.map("-" * _).mkString("  ")
    (renderRow(headers) :: separator :: rows.map(renderRow)).mkString("\n")

@main def releaseAgeScalaMinorDistribution(args: String*): Unit =
  ReleaseAgeScalaMinorDistributionApp.run(args.toList)
