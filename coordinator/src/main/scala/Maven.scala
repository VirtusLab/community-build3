import org.jsoup.Jsoup
import scala.jdk.CollectionConverters.*
import scala.collection.concurrent.TrieMap
import scala.util.{Success, Try}
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Maven Central coordinate resolution and POM loading (no Scaladex). */
object Maven:
  private val HttpTimeoutMs = 60_000
  private val groupIdCache = TrieMap.empty[String, String]

  def scalaArtifactId(module: String, scalaBinaryVersion: String): String =
    if module.endsWith(s"_$scalaBinaryVersion") then module
    else s"${module}_$scalaBinaryVersion"

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
    val orgPath = groupId.split('.').mkString("/")
    s"https://repo1.maven.org/maven2/$orgPath/$artifactId/$version/$artifactId-$version.pom"

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
