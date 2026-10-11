import java.util.concurrent.TimeUnit.{MILLISECONDS, SECONDS}
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
      case _                       =>
        Option(err.getCause).exists(isJsonParseFailure)

  private case class SearchHit(
      organization: String,
      repository: String,
      stars: Option[Int] = None
  ) derives Reader
  private case class SearchPagination(current: Int, pageCount: Int, totalSize: Long) derives Reader
  private case class SearchPage(pagination: SearchPagination, items: Seq[SearchHit]) derives Reader

class Scaladex:
  import Scaladex.*

  private val backend = DefaultSyncBackend(BackendOptions.Default.connectionTimeout(1.minute))
  private val UserAgent = "community-build3 (https://github.com/VirtusLab/community-build3)"

  /** Largest page size accepted by `/api/v1/search`. */
  private val SearchPageSize = 100

  /** HTML search pages sit behind a Cloudflare challenge that trips after a short burst. The JSON API uses fewer,
    * larger pages; this gap keeps that burst from starting.
    */
  private val SearchPageDelayMs = 500

  private def fetchRaw(uri: Uri): Response[String] =
    CoordinatorRuntime.withPermit(CoordinatorRuntime.scaladexApi) {
      quickRequest
        .get(uri)
        .header("User-Agent", UserAgent)
        .response(asStringAlways)
        .send(backend)
    }

  private def readBody[T: Reader](uri: Uri, response: Response[String]): T =
    if !response.isSuccess then throw HttpFailure(response.code.code, response.body.take(200), uri)
    read[T](response.body)

  private def waitSeconds(err: Throwable, backoffSeconds: Int): Int =
    err match
      case failure: HttpFailure if failure.statusCode == 429 => backoffSeconds.max(5)
      case _                                                 => backoffSeconds

  private def getSync[T: Reader](uri: Uri): T =
    def loop(backoffSeconds: Int): T =
      try readBody(uri, fetchRaw(uri))
      catch
        case err: SttpClientException =>
          pause(err, backoffSeconds, "fetch")
        case err: HttpFailure =>
          pause(err, backoffSeconds, "fetch")
        case err: Exception if isJsonParseFailure(err) =>
          pause(err, backoffSeconds, "parse")

    def pause(err: Throwable, backoffSeconds: Int, kind: String): T =
      val delaySeconds = waitSeconds(err, backoffSeconds)
      Console.err.println(
        s"Failed to $kind Scaladex response (${CoordinatorRuntime.describeFailure(err)}), retry with backoff ${delaySeconds}s for $uri"
      )
      SECONDS.sleep(delaySeconds)
      loop((delaySeconds * 2).min(60))

    loop(1)

  private inline def get[T: Reader](uri: Uri): AsyncResponse[T] =
    Future(getSync[T](uri))

  /** JVM projects for a Scaladex language filter (`3`, `2.13`, …), most-starred first. */
  def starredProjects(scalaBinaryVersion: String): Seq[StarredProject] =
    def page(pageNumber: Int): SearchPage =
      getSync[SearchPage](
        uri"$ScaladexUrl/api/v1/search?q=*&language=$scalaBinaryVersion&platform=jvm&sort=stars&page=$pageNumber&size=$SearchPageSize"
      )

    val first = page(1)
    val rest =
      if first.pagination.pageCount <= 1 then Seq.empty
      else
        (2 to first.pagination.pageCount).map { pageNumber =>
          MILLISECONDS.sleep(SearchPageDelayMs)
          page(pageNumber)
        }
    (first +: rest)
      .flatMap(_.items)
      .map(hit => StarredProject(hit.organization, hit.repository)(hit.stars.getOrElse(-1)))
      .sortBy(-_.stars)

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
