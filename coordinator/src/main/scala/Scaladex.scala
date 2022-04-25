import java.time.ZonedDateTime
import scala.concurrent.*

object Scaladex {
  case class Pagination(current: Int, pageCount: Int, totalSize: Int)
  case class ArtifactMetadata(version: String)
  case class ArtifactMetadataResponse(pagination: Pagination, items: List[ArtifactMetadata])
  case class ProjectSummary(
      groupId: String,
      artifacts: List[String], // List of artifacts with suffixes
      version: String, // latest known versions
      versions: List[String] // all published versions
  )

  final val ScaladexUrl = "https://index.scala-lang.org"
  type AsyncResponse[T] = ExecutionContext ?=> Future[T]

  def artifactMetadata(
      groupId: String,
      artifactId: String
  ): AsyncResponse[ArtifactMetadataResponse] =
    Future {
      val response = requests.get(
        url = s"$ScaladexUrl/api/artifacts/$groupId/$artifactId"
      )
      fromJson[ArtifactMetadataResponse](response.text())
    }

  def projectSummary(
      organization: String,
      repository: String,
      scalaBinaryVersion: String
  ): AsyncResponse[Option[ProjectSummary]] = Future {
    val response = requests.get(
      url = s"$ScaladexUrl/api/project",
      params = Map(
        "organization" -> organization,
        "repository" -> repository,
        "target" -> "JVM",
        "scalaVersion" -> scalaBinaryVersion
      )
    )
    // If output is empty it means that given project does not define JVM modules
    // for given scala version
    Option.unless(response.contentLength.contains(0)) {
      fromJson[ProjectSummary](response.text())
    }
  }

}
