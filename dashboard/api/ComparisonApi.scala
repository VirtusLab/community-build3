package dashboard.api

import cats.effect.IO

import dashboard.core.*
import dashboard.data.ElasticsearchClient

/** Service for comparing builds */
class ComparisonApi(esClient: ElasticsearchClient):

  /** Compare two builds and return the differences */
  def compare(request: CompareRequest): IO[Either[String, ComparisonResult]] =
    val validation = validateRequest(request)
    validation match
      case Left(error) => IO.pure(Left(error))
      case Right(_)    =>
        for
          baseBuilds <- getBuilds(request.baseScalaVersion, request.baseBuildId)
          targetBuilds <- getBuilds(request.targetScalaVersion, request.targetBuildId)
          result = computeComparison(request, baseBuilds, targetBuilds)
        yield Right(result)

  private def validateRequest(request: CompareRequest): Either[String, Unit] =
    val hasTarget = request.targetScalaVersion.isDefined || request.targetBuildId.isDefined

    if !hasTarget then Left("Target scala version or build ID is required")
    else Right(())

  private def getBuilds(
      scalaVersion: Option[String],
      buildId: Option[String]
  ): IO[List[BuildResult]] =
    (scalaVersion, buildId) match
      case (_, Some(bid)) => esClient.getBuildsByBuildId(bid)
      case (Some(sv), _)  => esClient.getBuildsByScalaVersion(sv)
      case (None, None)   => IO.pure(Nil)

  private def computeComparison(
      request: CompareRequest,
      baseBuilds: List[BuildResult],
      targetBuilds: List[BuildResult]
  ): ComparisonResult =
    // Group by project name, taking the most recent build per project
    val baseByProject = baseBuilds
      .groupBy(_.projectName)
      .view
      .mapValues(_.maxBy(_.timestamp))
      .toMap

    val targetByProject = targetBuilds
      .groupBy(_.projectName)
      .view
      .mapValues(_.maxBy(_.timestamp))
      .toMap

    val allProjects = baseByProject.keySet ++ targetByProject.keySet

    val (newFailures, newFixes, stillFailing, stillPassing) =
      allProjects.foldLeft((List.empty[ProjectDiff], List.empty[ProjectDiff], List.empty[ProjectDiff], 0)):
        case ((failures, fixes, failing, passing), project) =>
          val base = baseByProject.get(project)
          val target = targetByProject.get(project)

          (base.map(_.status), target.map(_.status)) match
            // New failure: was success (or not present) -> now failure
            case (Some(BuildStatus.Success), Some(BuildStatus.Failure)) | (None, Some(BuildStatus.Failure)) =>
              val diff = createDiff(project, base, target.get)
              (diff :: failures, fixes, failing, passing)

            // Fixed: was failure -> now success
            case (Some(BuildStatus.Failure), Some(BuildStatus.Success)) =>
              val diff = createDiff(project, base, target.get)
              (failures, diff :: fixes, failing, passing)

            // Still failing
            case (Some(BuildStatus.Failure), Some(BuildStatus.Failure)) =>
              val diff = createDiff(project, base, target.get)
              (failures, fixes, diff :: failing, passing)

            // Still passing
            case (Some(BuildStatus.Success), Some(BuildStatus.Success)) =>
              (failures, fixes, failing, passing + 1)

            // Other cases (started, etc.)
            case _ =>
              (failures, fixes, failing, passing)

    ComparisonResult(
      baseScalaVersion = request.baseScalaVersion,
      baseBuildId = request.baseBuildId,
      targetScalaVersion = request.targetScalaVersion,
      targetBuildId = request.targetBuildId,
      newFailures = newFailures.sortBy(_.projectName: String),
      newFixes = newFixes.sortBy(_.projectName: String),
      stillFailing = stillFailing.sortBy(_.projectName: String),
      stillPassing = stillPassing
    )

  private def createDiff(
      project: ProjectName,
      base: Option[BuildResult],
      target: BuildResult
  ): ProjectDiff =
    ProjectDiff(
      projectName = project,
      version = target.version,
      baseStatus = base.map(_.status),
      targetStatus = target.status,
      failureReasons = target.failureReasons,
      buildURL = target.buildURL,
      buildId = target.buildId
    )
