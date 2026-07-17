import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import scala.util.Try

/** Disk cache for stable [[ProjectBuildDef]] fields (repoUrl, revision, config).
  *
  * Invalidated when the published Maven version changes or coordinator-side config
  * inputs affecting the project change. Graph-derived fields are always recomputed.
  *
  * Limitation: force-pushed git tags without a new Maven version can leave stale
  * revision/sourcePatches — use `--refresh-projects=org/repo` to bypass the cache.
  */
case class CachedProjectBuildDef(
    version: String,
    fingerprint: String,
    repoUrl: String,
    revision: String,
    config: Option[ProjectBuildConfig]
)

case class CoordinatorCacheOptions(
    enabled: Boolean = true,
    refreshProjects: Set[Project] = Set.empty,
    seedBuildConfigPath: os.Path = workflowsDir / "buildConfig.json"
)

object CoordinatorCacheOptions:
  def parse(varargs: Seq[String]): CoordinatorCacheOptions =
    val refreshProjects =
      varargs
        .collectFirst { case s"--refresh-projects=${list}" =>
          list.split(",").filter(_.nonEmpty).map(Project.load).toSet
        }
        .getOrElse(Set.empty)
    CoordinatorCacheOptions(
      enabled = !varargs.contains("--no-project-cache"),
      refreshProjects = refreshProjects
    )

/** Parsed [[buildConfig.json]] once per coordinator run (avoids OOM from parallel full-file parses).
  *
  * Used only to seed repoUrl/revision when the disk cache is cold. Config is never taken from
  * the seed file — it is always rediscovered so edits to projects-config.conf are picked up.
  */
final class BuildConfigSeedIndex(path: os.Path):
  private lazy val byCoordinates: Map[String, ProjectBuildDef] =
    if !os.exists(path) then Map.empty
    else
      println(s"Loading build config seed from $path")
      Try(fromJson[Map[String, ProjectBuildDef]](os.read(path))).fold(
        ex =>
          System.err.println(s"Failed to load build config seed: $ex")
          Map.empty
        ,
        identity
      )

  def seedRepoRevision(
      project: Project,
      version: String,
      stats: ProjectBuildDefCacheStats
  ): Option[(String, String)] =
    byCoordinates.get(project.coordinates).flatMap { defn =>
      if defn.version != version then None
      else
        stats.seededFromBuildConfig.incrementAndGet()
        Some((defn.repoUrl, defn.revision))
    }

final class ProjectBuildDefCacheStats:
  val hits = AtomicInteger(0)
  val missesVersion = AtomicInteger(0)
  val missesConfig = AtomicInteger(0)
  val seededFromBuildConfig = AtomicInteger(0)
  val refreshed = AtomicInteger(0)

  def report(): Unit =
    println(
      s"Project build def cache: hits=${hits.get()}, misses(version)=${missesVersion.get()}, misses(config)=${missesConfig.get()}, seeded=${seededFromBuildConfig.get()}, forcedRefresh=${refreshed.get()}"
    )

object ProjectBuildDefCache:
  private val cacheDir = dataPath.resolve("projectBuildDefs")

  def dest(project: Project): java.nio.file.Path =
    cacheDir.resolve(s"${project.organization}_${project.repository}.json")

  def load(project: Project): Option[CachedProjectBuildDef] =
    val path = dest(project)
    if !Files.exists(path) then None
    else
      Try(fromJson[CachedProjectBuildDef](Files.readString(path))).toOption

  def save(entry: CachedProjectBuildDef, project: Project): Unit =
    val path = dest(project)
    Files.createDirectories(path.getParent)
    Files.writeString(path, toJson(entry, pretty = true))

  def computeFingerprint(
      project: Project,
      confFiles: ConfigFiles,
      discovery: ProjectConfigDiscovery
  ): String =
    val digest = MessageDigest.getInstance("SHA-256")
    def update(label: String, bytes: Array[Byte]): Unit =
      digest.update(label.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      digest.update(0.toByte)
      digest.update(bytes)
      digest.update(0.toByte)

    update("project-config", discovery.projectConfigFingerprintBytes(project.show))
    discovery.requireConfigBytes(project).foreach { case (label, bytes) =>
      update(label, bytes)
    }
    update("replacement", replacementLine(project, confFiles).getBytes(java.nio.charset.StandardCharsets.UTF_8))
    digest.digest().map("%02x".format(_)).mkString

  private def replacementLine(project: Project, confFiles: ConfigFiles): String =
    val pattern = raw"(\S+)/(\S+) (\S+)/(\S+) ?(\S+)?".r
    if !os.exists(confFiles.replacedProjects) || os.isDir(confFiles.replacedProjects) then ""
    else
      readNormalized(confFiles.replacedProjects)
        .collectFirst {
          case line @ pattern(org1, name1, _, _, _) if org1 == project.organization && name1 == project.repository =>
            line
        }
        .getOrElse("")

  def resolveStableFields(
      project: ProjectVersion,
      fingerprint: String,
      repoUrlForProject: Project => String,
      resolveRevision: String => Option[Git.Revision],
      configDiscovery: (ProjectVersion, String, Option[Git.Revision]) => Option[ProjectBuildConfig],
      options: CoordinatorCacheOptions,
      buildConfigSeed: BuildConfigSeedIndex,
      stats: ProjectBuildDefCacheStats
  ): (String, String, Option[ProjectBuildConfig]) =
    val forceRefresh = options.refreshProjects.contains(project.p)
    val useCache = options.enabled && !forceRefresh

    if forceRefresh then stats.refreshed.incrementAndGet()

    def discoverFresh(
        reuseFromCache: Option[CachedProjectBuildDef] = None
    ): (String, String, Option[ProjectBuildConfig]) =
      val repoUrl = reuseFromCache.fold(repoUrlForProject(project.p))(_.repoUrl)
      val revision =
        reuseFromCache
          .flatMap(e => Git.revisionFromCached(e.revision))
          .orElse(resolveRevision(repoUrl))
      println(s"Discovering config for ${project.p.coordinates} (git checkout)...")
      val config = configDiscovery(project, repoUrl, revision)
      val entry = CachedProjectBuildDef(
        version = project.v,
        fingerprint = fingerprint,
        repoUrl = repoUrl,
        revision = revision.map(_.stringValue).getOrElse(""),
        config = config
      )
      if options.enabled then save(entry, project.p)
      (entry.repoUrl, entry.revision, entry.config)

    if !useCache then discoverFresh()
    else
      load(project.p) match
        // A cache hit with config=None would permanently skip migrationVersions / tests /
        // sourceVersion discovery (run.sh then passes {}). Treat as a miss and rediscover.
        case Some(entry)
            if entry.version == project.v && entry.fingerprint == fingerprint && entry.config.isDefined =>
          stats.hits.incrementAndGet()
          println(s"Skipping config discovery for ${project.p.coordinates} (cache hit)")
          (entry.repoUrl, entry.revision, entry.config)
        case Some(entry) if entry.version == project.v && entry.fingerprint == fingerprint =>
          stats.missesConfig.incrementAndGet()
          println(
            s"Refreshing ${project.p.coordinates} (cache hit had no config; rediscovering)"
          )
          discoverFresh(reuseFromCache = Some(entry))
        case Some(entry) if entry.version != project.v =>
          stats.missesVersion.incrementAndGet()
          println(
            s"Refreshing ${project.p.coordinates} (version ${entry.version} -> ${project.v})"
          )
          discoverFresh()
        case Some(entry) =>
          stats.missesConfig.incrementAndGet()
          println(s"Refreshing ${project.p.coordinates} (coordinator config changed)")
          discoverFresh(reuseFromCache = Some(entry))
        case None =>
          buildConfigSeed.seedRepoRevision(project.p, project.v, stats) match
            case Some((repoUrl, revision)) =>
              println(
                s"Refreshing ${project.p.coordinates} (buildConfig.json seed for repo/revision only)"
              )
              discoverFresh(
                reuseFromCache = Some(
                  CachedProjectBuildDef(
                    version = project.v,
                    fingerprint = fingerprint,
                    repoUrl = repoUrl,
                    revision = revision,
                    config = None
                  )
                )
              )
            case None =>
              println(s"Refreshing ${project.p.coordinates} (no cache entry)")
              discoverFresh()

end ProjectBuildDefCache
