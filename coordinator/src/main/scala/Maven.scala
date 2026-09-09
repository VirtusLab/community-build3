import org.jsoup.Jsoup
import org.jsoup.HttpStatusException
import scala.jdk.CollectionConverters.*
import scala.collection.concurrent.TrieMap
import scala.util.{Failure, Success, Try}
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate

/** Maven Central coordinate resolution, version listing, and POM loading (no Scaladex). */
object Maven:
  private val HttpTimeoutMs = 60_000
  private val groupIdCache = TrieMap.empty[String, String]
  private val versionsCache = TrieMap.empty[(String, String), VersionsLookup]
  private val VersionTag = """<version>([^<]+)</version>""".r

  /** Published version on Maven Central, optionally with the directory listing date. */
  case class MavenVersion(version: String, releaseDate: Option[LocalDate])

  /** Outcome of listing versions for a GAV on Maven Central. */
  enum VersionsLookup:
    /** Metadata (and optional dates) loaded successfully. May be empty if Central has no versions. */
    case Resolved(versions: Seq[MavenVersion])
    /** HTTP 404 — artifact coordinate is not on Central. */
    case NotFound
    /** Network/parse/other failure; not the same as missing. */
    case Failure(cause: Throwable)

    def versionStrings: Seq[String] = this match
      case Resolved(versions) => versions.map(_.version)
      case NotFound | Failure(_) => Nil

  def scalaArtifactId(module: String, scalaBinaryVersion: String): String =
    if module.endsWith(s"_$scalaBinaryVersion") then module
    else s"${module}_$scalaBinaryVersion"

  def artifactBaseUrl(groupId: String, artifactId: String): String =
    val orgPath = groupId.split('.').mkString("/")
    s"https://repo1.maven.org/maven2/$orgPath/$artifactId"

  def metadataUrl(groupId: String, artifactId: String): String =
    s"${artifactBaseUrl(groupId, artifactId)}/maven-metadata.xml"

  /** Match buildConfig `targets` (`org%artifact`); artifactId always has the Scala binary suffix. */
  def coordsFromSeed(
      project: Project,
      module: String,
      scalaBinaryVersion: String,
      buildConfigSeed: BuildConfigSeedIndex
  ): Option[(String, String)] =
    val artifactId = scalaArtifactId(module, scalaBinaryVersion)
    val suffix = s"_$scalaBinaryVersion"
    val moduleBase = artifactId.stripSuffix(suffix)
    buildConfigSeed
      .targets(project)
      .iterator
      .collectFirst:
        case (group, artifact)
            if
              val artifactBase =
                if artifact.endsWith(suffix) then artifact.stripSuffix(suffix) else artifact
              artifactBase == moduleBase || artifact == module || artifact == artifactId
            =>
          (group, artifactId)

  /** Resolve Maven groupId via search.maven.org (artifactId → groupId). */
  def lookupGroupId(artifactId: String): Try[String] =
    Try:
      groupIdCache.getOrElseUpdate(
        artifactId, {
          val q = URLEncoder.encode(s"""a:"$artifactId"""", StandardCharsets.UTF_8)
          val url = s"https://search.maven.org/solrsearch/select?q=$q&rows=5&wt=json"
          val body = Jsoup
            .connect(url)
            .ignoreContentType(true)
            .timeout(HttpTimeoutMs)
            .execute()
            .body()
          val docs = ujson.read(body)("response")("docs").arr
          docs
            .map(_.obj)
            .find(_.get("a").exists(_.str == artifactId))
            .flatMap(_.get("g").map(_.str))
            .getOrElse:
              throw RuntimeException(s"No Maven Central artifact found for artifactId=$artifactId")
        }
      )

  def resolveCoords(
      project: Project,
      module: String,
      scalaBinaryVersion: String,
      buildConfigSeed: BuildConfigSeedIndex
  ): Try[(String, String)] =
    coordsFromSeed(project, module, scalaBinaryVersion, buildConfigSeed) match
      case Some(coords) => Success(coords)
      case None =>
        val artifactId = scalaArtifactId(module, scalaBinaryVersion)
        lookupGroupId(artifactId).map(_ -> artifactId)

  def pomUrl(groupId: String, artifactId: String, version: String): String =
    s"${artifactBaseUrl(groupId, artifactId)}/$version/$artifactId-$version.pom"

  /** Version strings from `maven-metadata.xml` (no directory dates). */
  def listVersions(groupId: String, artifactId: String): VersionsLookup =
    listVersionsWithDates(groupId, artifactId, fetchDates = false)

  /** Versions from Central, optionally with directory-listing dates for `--release-cutoff`. */
  def listVersionsWithDates(
      groupId: String,
      artifactId: String,
      fetchDates: Boolean = true
  ): VersionsLookup =
    val key = (groupId, artifactId)
    versionsCache.get(key) match
      case Some(cached @ VersionsLookup.Resolved(versions))
          if !fetchDates || versions.forall(_.releaseDate.isDefined) || versions.isEmpty =>
        cached
      case Some(VersionsLookup.NotFound) => VersionsLookup.NotFound
      case _ =>
        val lookup =
          fetchMetadataVersions(groupId, artifactId) match
            case Failure(ex: HttpStatusException) if ex.getStatusCode == 404 =>
              VersionsLookup.NotFound
            case Failure(ex) =>
              VersionsLookup.Failure(ex)
            case Success(versionStrings) =>
              if !fetchDates || versionStrings.isEmpty then
                VersionsLookup.Resolved(versionStrings.map(MavenVersion(_, None)))
              else
                fetchDirectoryDates(groupId, artifactId) match
                  case Failure(ex) => VersionsLookup.Failure(ex)
                  case Success(dates) =>
                    VersionsLookup.Resolved(versionStrings.map(v => MavenVersion(v, dates.get(v))))
        lookup match
          case err: VersionsLookup.Failure => err // do not cache transient failures
          case cacheable =>
            versionsCache.update(key, cacheable)
            cacheable

  private def fetchMetadataVersions(groupId: String, artifactId: String): Try[Seq[String]] =
    Try:
      CoordinatorRuntime.withPermit(CoordinatorRuntime.mavenInfo):
        val metadataBody =
          Jsoup
            .connect(metadataUrl(groupId, artifactId))
            .ignoreContentType(true)
            .timeout(HttpTimeoutMs)
            .execute()
            .body()
        VersionTag.findAllMatchIn(metadataBody).map(_.group(1)).toSeq.distinct

  private def fetchDirectoryDates(
      groupId: String,
      artifactId: String
  ): Try[Map[String, LocalDate]] =
    Try:
      CoordinatorRuntime.withPermit(CoordinatorRuntime.mavenInfo):
        val doc =
          Jsoup
            .connect(s"${artifactBaseUrl(groupId, artifactId)}/")
            .timeout(HttpTimeoutMs)
            .get()
        // repo1 listings: `<a href="2.4.0/">2.4.0/</a>                                         2025-05-27 09:40         -`
        val DatePrefixed = """^\s*(\d{4}-\d{2}-\d{2})\s+\d{2}:\d{2}\s""".r
        doc
          .select("a[href]")
          .asScala
          .flatMap { link =>
            val href = link.attr("href")
            Option.when(href.endsWith("/") && href != "../") {
              val version = href.stripSuffix("/")
              val afterLink = Option(link.nextSibling).map(_.toString).getOrElse("")
              DatePrefixed
                .findFirstMatchIn(afterLink)
                .flatMap(m => Try(LocalDate.parse(m.group(1))).toOption)
                .map(version -> _)
            }.flatten
          }
          .toMap

  /** Load module POM deps from Maven Central. */
  def asTarget(scalaBinaryVersion: String, buildConfigSeed: BuildConfigSeedIndex)(
      mv: ModuleVersion
  ): Target =
    import mv.*
    CoordinatorRuntime.withPermit(CoordinatorRuntime.mavenInfo) {
      val (groupId, artifactId) =
        resolveCoords(p, name, scalaBinaryVersion, buildConfigSeed).get
      val md = Jsoup.connect(pomUrl(groupId, artifactId, version)).timeout(HttpTimeoutMs).get

      val deps =
        for
          dep <- md.select("dependency").asScala
          depGroupId <- dep.select("groupId").asScala
          depArtifactId <- dep.select("artifactId").asScala
          depVersion <- dep.select("version").asScala
        yield Dep(TargetId(depGroupId.text, depArtifactId.text), depVersion.text)

      Target(TargetId(groupId, artifactId), deps.toSeq)
    }

  /** Resolve coordinates without loading a POM (used when Central has no published version). */
  def asStubTarget(scalaBinaryVersion: String, buildConfigSeed: BuildConfigSeedIndex)(
      mv: ModuleVersion
  ): Try[Target] =
    resolveCoords(mv.p, mv.name, scalaBinaryVersion, buildConfigSeed).map:
      case (groupId, artifactId) => Target(TargetId(groupId, artifactId), Nil)
