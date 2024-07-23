import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit.SECONDS
import scala.concurrent.*
import scala.concurrent.duration.*
import java.io.IOException

object Scaladex {
  case class Pagination(current: Int, pageCount: Int, totalSize: Int)
  // releaseDate is always UTC zoned
  case class ArtifactMetadata(
      version: String,
      releaseDate: java.time.OffsetDateTime
  )
  case class ArtifactMetadataResponse(
      pagination: Pagination,
      items: List[ArtifactMetadata]
  )
  case class ProjectSummary(
      groupId: String,
      artifacts: List[String], // List of artifacts with suffixes
      version: String, // latest known versions
      versions: List[String] // all published versions
  )

  final val ScaladexUrl = "https://index.scala-lang.org"

  def artifactMetadata(
      groupId: String,
      artifactId: String
  ): AsyncResponse[ArtifactMetadataResponse] = {
    def tryFetch(backoffSeconds: Int): AsyncResponse[ArtifactMetadataResponse] =
      Future {
        val response = requests.get(
          url = s"$ScaladexUrl/api/artifacts/$groupId/$artifactId"
        )
        fromJson[ArtifactMetadataResponse](response.text())
      }.recoverWith {
        case err: org.jsoup.HttpStatusException
            if err.getStatusCode == 503 && !Thread.interrupted() =>
          Console.err.println(
            s"Failed to fetch artifact metadata, Scaladex unavailable, retry with backoff ${backoffSeconds}s for $groupId:$artifactId"
          )
          SECONDS.sleep(backoffSeconds)
          tryFetch((backoffSeconds * 2).min(60))
        case _: requests.TimeoutException =>
          Console.err.println(
            s"Failed to fetch artifact metadata, Scaladex request timeout, retry with backoff ${backoffSeconds}s for $groupId:$artifactId"
          )
          SECONDS.sleep(backoffSeconds)
          tryFetch((backoffSeconds * 2).min(60))
        case e: requests.RequestsException if e.getMessage.contains("GOWAY") => 
          Console.err.println(
            s"Failed to fetch artifact metadata, Scaladex request terminated, retry with backoff ${backoffSeconds}s for $groupId:$artifactId"
          )
          SECONDS.sleep(backoffSeconds)
          tryFetch((backoffSeconds * 2).min(60))
      }
    tryFetch(1)
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
  }.recoverWith{
    case _: requests.TimeoutException => 
      Thread.sleep(scala.util.Random.nextInt(10.seconds.toMillis.toInt))
      projectSummary(organization, repository, scalaBinaryVersion)
  }

}
