import java.util.concurrent.TimeUnit.SECONDS
import scala.concurrent.*
import scala.concurrent.duration.*
import java.time.LocalDate
import sttp.client4.*
import sttp.model.Uri
import upickle.default.*

object Scaladex:
  final val ScaladexUrl = uri"https://index.scala-lang.org"

  case class ProjectArtifact(groupId: String, artifactId: String, version: String) derives Reader

  /** Non-2xx Scaladex response; body is often plain text (e.g. Cloudflare 503). */
  final class HttpFailure(val statusCode: Int, val bodyPreview: String, val uri: Uri)
      extends RuntimeException(s"HTTP $statusCode for $uri: $bodyPreview")

  /** upickle wraps `ujson.ParseException` in `TraceVisitor$TraceException`. */
  def isJsonParseFailure(err: Throwable): Boolean =
    err match
      case _: ujson.ParseException => true
      case _ =>
        Option(err.getCause).exists(isJsonParseFailure)

class Scaladex:
  import Scaladex.*

  private val backend = DefaultSyncBackend(BackendOptions.Default.connectionTimeout(1.minute))

  private inline def get[T: Reader](
      uri: Uri
  ): AsyncResponse[T] = {
    def retryAfter(err: Throwable, backoffSeconds: Int, kind: String): AsyncResponse[T] =
      Console.err.println(
        s"Failed to $kind artifact metadata (${CoordinatorRuntime.describeFailure(err)}), retry with backoff ${backoffSeconds}s for $uri"
      )
      SECONDS.sleep(backoffSeconds)
      tryGet((backoffSeconds * 2).min(60))

    def tryGet(backoffSeconds: Int): AsyncResponse[T] = Future {
      CoordinatorRuntime.withPermit(CoordinatorRuntime.scaladexApi) {
        val response = quickRequest
          .get(uri)
          .response(asStringAlways)
          .send(backend)
        if !response.isSuccess then
          throw HttpFailure(response.code.code, response.body.take(200), uri)
        read[T](response.body)
      }
    }.recoverWith {
      case err: SttpClientException =>
        retryAfter(err, backoffSeconds, "fetch")
      case err: HttpFailure =>
        retryAfter(err, backoffSeconds, "fetch")
      case err: Exception if isJsonParseFailure(err) =>
        retryAfter(err, backoffSeconds, "parse")
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
