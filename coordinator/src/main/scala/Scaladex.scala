import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import scala.concurrent.*
import scala.concurrent.duration.*
import java.time.LocalDate
import sttp.client4.*
import sttp.model.Uri
import upickle.default.*

object Scaladex:
  final val ScaladexUrl = uri"https://index.scala-lang.org"

  case class ProjectArtifact(groupId: String, artifactId: String, version: String) derives Reader

  /** Non-2xx Scaladex response. Body is omitted: Cloudflare 429 pages are large HTML challenges. */
  final class HttpFailure(val statusCode: Int, val uri: Uri)
      extends RuntimeException(s"HTTP $statusCode for $uri")

  /** One URL exhausted its attempts. Callers may skip that project and keep a cached copy. */
  final class GaveUp(val uri: Uri, val attempts: Int, cause: Throwable)
      extends RuntimeException(s"Giving up on Scaladex after $attempts attempts for $uri (${cause.getMessage})", cause)

  /** Shared backoff still saw errors. Fail the run instead of retrying until the job is cancelled. */
  final class Unavailable(message: String) extends RuntimeException(message)

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

  /** Floor between successful calls. A cold run lists ~2k projects; faster than this trips Cloudflare 429s. */
  private val MinGapMs = 350
  private val MaxGapMs = 15_000
  private val MaxAttempts = 4
  private val CircuitAfter = 8
  private val MaxCircuitOpens = 4
  private val CircuitPauseMs = 30_000

  private val nextRequestAtMs = AtomicLong(0L)
  private val gapMs = AtomicInteger(MinGapMs)
  private val consecutiveFailures = AtomicInteger(0)
  private val circuitOpens = AtomicInteger(0)

  private enum Fetch:
    case Ok(body: String)
    case Http(status: Int)
    case Transport(err: SttpClientException)

  /** One Scaladex call at a time, including the backoff. Independent retries were re-entering as soon as the previous
    * 429 returned, so a few thousand project lookups hammered Cloudflare for the whole 6h job timeout.
    */
  private def fetchRaw(uri: Uri): Fetch =
    CoordinatorRuntime.withPermit(CoordinatorRuntime.scaladexApi) {
      val waitMs = nextRequestAtMs.get() - System.currentTimeMillis()
      if waitMs > 0 then MILLISECONDS.sleep(waitMs)
      try
        val response =
          quickRequest
            .get(uri)
            .header("User-Agent", UserAgent)
            .response(asStringAlways)
            .send(backend)
        if response.isSuccess then
          recordSuccess()
          Fetch.Ok(response.body)
        else
          recordFailure()
          Fetch.Http(response.code.code)
      catch
        case err: SttpClientException =>
          recordFailure()
          Fetch.Transport(err)
    }

  private def recordSuccess(): Unit =
    consecutiveFailures.set(0)
    val decayed = math.max(MinGapMs, gapMs.get() - 25)
    gapMs.set(decayed)
    nextRequestAtMs.set(System.currentTimeMillis() + decayed)

  private def recordFailure(): Unit =
    val failures = consecutiveFailures.incrementAndGet()
    val grown = math.min(MaxGapMs, math.max(gapMs.get() * 2, 1000))
    gapMs.set(grown)
    if failures >= CircuitAfter then
      consecutiveFailures.set(0)
      val opens = circuitOpens.incrementAndGet()
      if opens > MaxCircuitOpens then
        throw Unavailable(
          s"Scaladex still failing after $MaxCircuitOpens pauses of ${CircuitPauseMs}ms"
        )
      Console.err.println(
        s"[scaladex] $CircuitAfter consecutive errors, pausing ${CircuitPauseMs}ms (cycle $opens/$MaxCircuitOpens)"
      )
      nextRequestAtMs.set(System.currentTimeMillis() + CircuitPauseMs)
    else nextRequestAtMs.set(System.currentTimeMillis() + grown)

  private def getSync[T: Reader](uri: Uri): T =
    def loop(attempt: Int): T =
      fetchRaw(uri) match
        case Fetch.Ok(body) =>
          try read[T](body)
          catch
            case err: Exception if isJsonParseFailure(err) =>
              recordFailure()
              retry(attempt, err, s"JSON parse failed for $uri")
        case Fetch.Http(status) =>
          retry(attempt, HttpFailure(status, uri), s"HTTP $status for $uri")
        case Fetch.Transport(err) =>
          retry(attempt, err, s"${CoordinatorRuntime.describeFailure(err)} for $uri")

    def retry(attempt: Int, err: Throwable, detail: String): T =
      if attempt >= MaxAttempts then throw GaveUp(uri, attempt, err)
      Console.err.println(
        s"Scaladex $detail (attempt $attempt/$MaxAttempts, next gap ${gapMs.get()}ms)"
      )
      loop(attempt + 1)

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
