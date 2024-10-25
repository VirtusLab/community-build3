import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit.SECONDS
import scala.concurrent.*
import scala.concurrent.duration.*
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import sttp.client4.*
import sttp.model.Uri
import upickle.default.*
import scala.annotation.nowarn

object Scaladex:
  final val ScaladexUrl = uri"https://index.scala-lang.org"

class Scaladex(using ExecutionContext):
  import Scaladex.*

  private val backend = DefaultSyncBackend(BackendOptions.Default.connectionTimeout(1.minute))

  private inline def get[T: Reader](
      uri: Uri
  ): AsyncResponse[T] = {
    @nowarn("msg=New anonymous class definition will be duplicated at each inline site")
    def tryGet(backoffSeconds: Int): AsyncResponse[T] = Future {
      quickRequest
        .get(uri)
        .mapResponse(read[T](_))
        .send(backend)
    }.map(_.body)
      .recoverWith { case err: SttpClientException =>
        Console.err.println(
          s"Failed to fetch artifact metadata, ${err.cause}, retry with backoff ${backoffSeconds}s for $uri"
        )
        SECONDS.sleep(backoffSeconds)
        tryGet((backoffSeconds * 2).min(60))
      }

    tryGet(1)
  }

  def projects: AsyncResponse[Seq[Project]] = {
    case class ProjectEntry(organization: String, repository: String) derives Reader
    get[List[ProjectEntry]](uri"$ScaladexUrl/api/projects")
      .map:
        _.map:
          case ProjectEntry(organization, repository) =>
            Project(organization, repository)
  }

  case class ProjectArtifact(groupId: String, artifactId: String, version: String) derives Reader
  def artifacts(project: Project): AsyncResponse[Seq[ProjectArtifact]] =
    get[Seq[ProjectArtifact]](
      uri"$ScaladexUrl/api/projects/${project.organization}/${project.repository}/artifacts?stable-only=false"
    )

  case class Artifact(
      groupId: String,
      artifactId: String,
      version: String,
      name: String,
      project: Project,
      releaseDate: java.time.OffsetDateTime, // epoch-millis
      licenses: Seq[String],
      language: String,
      platform: String
  ) derives Reader:
    def releaseLocalData: LocalDate = LocalDate.from(releaseDate)

  case class ScaladexProject(organization: String, repository: String) derives Reader
  given Reader[java.time.OffsetDateTime] = summon[Reader[String]].map(java.time.OffsetDateTime.parse)
  given Reader[Project] = summon[Reader[ScaladexProject]].map: p =>
    Project(p.organization, p.repository)
  def artifact(artifact: ProjectArtifact): AsyncResponse[Artifact] =
    get[Artifact](
      uri"$ScaladexUrl/api/artifacts/${artifact.groupId}/${artifact.artifactId}/${artifact.version}"
    )

  case class ProjectSummary(
      groupId: String,
      artifacts: List[String], // List of artifacts with suffixes
      version: String, // latest known versions
      versions: List[String] // all published versions
  )
