import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit.SECONDS
import scala.concurrent.*
import scala.concurrent.duration.*
import java.io.IOException
import java.time.Instant
import java.time.LocalDate

object Scaladex {
  final val ScaladexUrl = "https://index.scala-lang.org"

  private def asyncGetWithRetry(url: String): AsyncResponse[requests.Response] = {
    def tryGet(backoffSeconds: Int): AsyncResponse[requests.Response] =
      Future { requests.get(url) }
        .recoverWith {
          case _: requests.TimeoutException =>
            Console.err.println(
              s"Failed to fetch artifact metadata, Scaladex request timeout, retry with backoff ${backoffSeconds}s for $url"
            )
            SECONDS.sleep(backoffSeconds)
            tryGet((backoffSeconds * 2).min(60))
          case e: requests.RequestsException if e.getMessage.contains("GOWAY") =>
            Console.err.println(
              s"Failed to fetch artifact metadata, Scaladex request terminated, retry with backoff ${backoffSeconds}s for $url"
            )
            SECONDS.sleep(backoffSeconds)
            tryGet((backoffSeconds * 2).min(60))
        }
    tryGet(1)
  }

  def projects: AsyncResponse[Seq[Project]] = {
    case class ProjectEntry(organization: String, repository: String)
    asyncGetWithRetry(s"$ScaladexUrl/api/projects")
      .map: response =>
        fromJson[List[ProjectEntry]](response.text())
          .map:
            case ProjectEntry(organization, repository) =>
              Project(organization, repository)
  }

  case class ProjectArtifact(groupId: String, artifactId: String, version: String)
  def artifacts(project: Project): AsyncResponse[Seq[ProjectArtifact]] =
    asyncGetWithRetry(s"$ScaladexUrl/api/projects/${project.org}/${project.name}/artifacts")
      .map: response =>
        fromJson[Seq[ProjectArtifact]](response.text())

  case class Artifact(
      groupId: String,
      artifactId: String,
      version: String,
      artifactName: String,
      project: String,
      releaseDate: Long, // epoch-millis
      licenses: Seq[String],
      language: String,
      platform: String
  ):
    def releaseLocalData: LocalDate = LocalDate.from(Instant.ofEpochMilli(releaseDate))

  def artifact(artifact: ProjectArtifact): AsyncResponse[Artifact] =
    asyncGetWithRetry(
      s"$ScaladexUrl/api/artifacts/${artifact.groupId}/${artifact.artifactId}/${artifact.version}"
    )
      .map: response =>
        fromJson[Artifact](response.text())

  case class ProjectSummary(
      groupId: String,
      artifacts: List[String], // List of artifacts with suffixes
      version: String, // latest known versions
      versions: List[String] // all published versions
  )
}
