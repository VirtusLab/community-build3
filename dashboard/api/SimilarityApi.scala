package dashboard.api

import cats.effect.IO
import cats.syntax.all.*

import dashboard.core.*
import dashboard.data.{ElasticsearchClient, SqliteRepository}

/** Service for failure similarity detection */
class SimilarityApi(esClient: ElasticsearchClient, sqliteRepo: SqliteRepository):

  /** Analyze a build's failures and store signature */
  def analyzeAndStore(projectNameRaw: String, buildId: String): IO[Either[String, FailureSignature]] =
    ProjectName(projectNameRaw) match
      case Left(error)        => IO.pure(Left(error))
      case Right(projectName) =>
        esClient
          .getBuildDetails(projectName, buildId)
          .flatMap:
            case None =>
              IO.pure(Left(s"Build not found: $projectNameRaw / $buildId"))
            case Some(result) =>
              result.logs match
                case None =>
                  IO.pure(Left("No logs available for this build"))
                case Some(logs) =>
                  val signature = FailureSignature.fromLogs(projectName, buildId, logs)
                  sqliteRepo.saveSignature(signature).as(Right(signature))

  /** Find builds with similar failures */
  def findSimilar(
      projectNameRaw: String,
      buildId: String
  ): IO[Either[String, SimilarityResult]] =
    ProjectName(projectNameRaw) match
      case Left(error)        => IO.pure(Left(error))
      case Right(projectName) =>
        sqliteRepo
          .getSignature(projectName, buildId)
          .flatMap:
            case None =>
              // Try to analyze on-the-fly
              esClient
                .getBuildDetails(projectName, buildId)
                .flatMap:
                  case None =>
                    IO.pure(Left(s"Build not found: $projectNameRaw / $buildId"))
                  case Some(result) =>
                    result.logs match
                      case None =>
                        IO.pure(Left("No logs available for this build"))
                      case Some(logs) =>
                        val signature = FailureSignature.fromLogs(projectName, buildId, logs)
                        findSimilarBySignature(signature)

            case Some(signature) =>
              findSimilarBySignature(signature)

  private def findSimilarBySignature(signature: FailureSignature): IO[Either[String, SimilarityResult]] =
    sqliteRepo
      .findSimilarFailures(signature.errorHash)
      .map: similar =>
        // Filter out the source build itself
        val otherFailures =
          similar.filter(s => s.projectName != signature.projectName || s.buildId != signature.buildId)

        Right(
          SimilarityResult(
            sourceSignature = signature,
            exactMatches = otherFailures,
            matchCount = otherFailures.length
          )
        )

  /** Check if a project is failing due to the same reason as before */
  def isSameFailure(
      projectNameRaw: String,
      currentBuildId: String,
      previousBuildId: String
  ): IO[Either[String, Boolean]] =
    ProjectName(projectNameRaw) match
      case Left(error)        => IO.pure(Left(error))
      case Right(projectName) =>
        for
          currentSig <- getOrCreateSignature(projectName, currentBuildId)
          previousSig <- getOrCreateSignature(projectName, previousBuildId)
        yield (currentSig, previousSig) match
          case (Right(curr), Right(prev)) =>
            Right(curr.isSimilarTo(prev))
          case (Left(err), _) => Left(err)
          case (_, Left(err)) => Left(err)

  private def getOrCreateSignature(
      projectName: ProjectName,
      buildId: String
  ): IO[Either[String, FailureSignature]] =
    sqliteRepo
      .getSignature(projectName, buildId)
      .flatMap:
        case Some(sig) => IO.pure(Right(sig))
        case None      =>
          esClient
            .getBuildDetails(projectName, buildId)
            .map:
              case None         => Left(s"Build not found: ${projectName} / $buildId")
              case Some(result) =>
                result.logs match
                  case None       => Left("No logs available")
                  case Some(logs) =>
                    Right(FailureSignature.fromLogs(projectName, buildId, logs))

  /** Batch analyze all failing builds from a build run */
  def analyzeFailingBuilds(buildId: String): IO[Int] =
    for
      builds <- esClient.getBuildsByBuildId(buildId)
      failures = builds.filter(_.status == BuildStatus.Failure)
      analyzed <- failures.traverse: build =>
        build.logs match
          case Some(logs) =>
            val sig = FailureSignature.fromLogs(build.projectName, build.buildId, logs)
            sqliteRepo.saveSignature(sig).as(1)
          case None =>
            IO.pure(0)
    yield analyzed.sum

/** Result of similarity search */
final case class SimilarityResult(
    sourceSignature: FailureSignature,
    exactMatches: List[FailureSignature],
    matchCount: Int
)
