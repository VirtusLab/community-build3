import scala.jdk.CollectionConverters.*
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import org.jsoup.Jsoup

/** Discover ZIO core modules that need a force-upgrade for Scala 3.10 Tracer fixes.
  * Skips ZIO 1.x and ZIO 2.0.0 milestones / RCs entirely. Only emits overrides when
  * current version &lt; [[ForceVersion]].
  */
object ZioDependencyOverrideDiscovery:
  val ForceVersion = "2.1.26"
  private val ForceSemVersion = SemVersion(2, 1, 26)

  /** Bump when discovery skip/force rules change so [[ProjectBuildDefCache]] invalidates. */
  val DiscoveryRulesVersion = "skip-1.x+skip-2.0.0-M/RC+force-2.1.26-7+transitive-zio-pom-graph"

  /** Core JVM modules from https://index.scala-lang.org/zio/zio/artifacts/zio
    * that share the `dev.zio` version line (excludes interop / examples / tests).
    *
    * Dependency graph at 2.1.26 (compile scope):
    * {{{
    * zio-stacktracer ─┐
    * zio-internal-macros ─┼─► zio ─┬─► zio-streams ─┬─► zio-managed ─► zio-macros
    *                      │        ├─► zio-concurrent│
    *                      │        └─► zio-test ─────┴─► zio-test-{sbt,junit,junit-engine,scalacheck,magnolia}
    *                                                      └─► zio-test-refined (via magnolia)
    * }}}
    * When any of these needs a force-upgrade we override the full set: eviction often
    * leaves companions (esp. stacktracer) pinned while nightlies fail to resolve them.
    */
  private val CoreArtifacts: Set[String] = Set(
    // roots / companions of zio
    "zio-stacktracer",
    "zio-internal-macros",
    "zio",
    // direct downstream of zio
    "zio-streams",
    "zio-concurrent",
    "zio-managed",
    "zio-macros",
    "zio-test",
    // downstream of zio-test
    "zio-test-sbt",
    "zio-test-junit",
    "zio-test-junit-engine",
    "zio-test-scalacheck",
    "zio-test-magnolia",
    "zio-test-refined",
  )

  private val Zio200PreRelease = raw"(?i)2\.0\.0-(?:M|RC)\d+.*".r
  private val QuotedVersion = raw""""(2\.\d+\.\d+[^"]*)"""".r
  private val ZioVersionAssign =
    raw"""(?i)zio(?:Version|Ver|_version)\s*=\s*"([^"]+)"""".r
  private val Placeholder = """\$\{([^}]+)\}""".r

  private case class ZioDep(logicalName: String, artifactId: String, version: String)

  private val DevZioPomDepsCache = TrieMap.empty[(String, String, String), List[ZioDep]]

  def discover(project: ProjectVersion, projectDir: os.Path): List[DependencyOverride] =
    try
      sourceSkipReason(projectDir) match
        case Some(reason) =>
          println(
            s"Skipping ZIO dependencyOverrides for ${project.p.coordinates}: $reason (from sources)"
          )
          Nil
        case None =>
          val directZioDeps = loadDirectZioMavenDeps(project)
          if directZioDeps.isEmpty then Nil
          else
            val coreDeps = reachableCoreDeps(directZioDeps)
            if coreDeps.isEmpty then Nil
            else if coreDeps.exists(dep => isZio1(dep.version)) then
              println(
                s"Skipping ZIO dependencyOverrides for ${project.p.coordinates}: uses ZIO 1.x (${coreDeps
                    .map(dep => s"${dep.logicalName}:${dep.version}")
                    .mkString(", ")})"
              )
              Nil
            else if coreDeps.exists(dep => isZio200PreRelease(dep.version)) then
              println(
                s"Skipping ZIO dependencyOverrides for ${project.p.coordinates}: uses ZIO 2.0.0 milestone / RC (${coreDeps
                    .map(dep => s"${dep.logicalName}:${dep.version}")
                    .mkString(", ")})"
              )
              Nil
            else if !coreDeps.exists(dep => needsForceUpgrade(dep.version)) then Nil
            else
              // Force the full core set once any reachable core dep needs it, even when
              // the project only declares a downstream/non-core `dev.zio` module directly.
              CoreArtifacts.toList
                .map(artifact => DependencyOverride.scala("dev.zio", artifact, ForceVersion))
                .distinctBy(_.moduleKey)
    catch
      case ex: Exception =>
        Console.err.println(
          s"Failed to discover ZIO dependencyOverrides for ${project.p.coordinates}: $ex"
        )
        Nil

  /** Prefer build sources over Maven: community-build compiles the git checkout. */
  private def sourceSkipReason(projectDir: os.Path): Option[String] =
    val text = buildSourceFiles(projectDir)
      .flatMap(path => util.Try(os.read(path)).toOption)
      .mkString("\n")
    if !text.contains("dev.zio") && !ZioVersionAssign.findFirstIn(text).isDefined then None
    else
      val assigned = ZioVersionAssign.findAllMatchIn(text).map(_.group(1)).toList
      val quoted =
        if text.contains("dev.zio") then
          QuotedVersion.findAllMatchIn(text).map(_.group(1)).toList
        else Nil
      val versions = (assigned ++ quoted).distinct
      if versions.exists(isZio1) then Some("uses ZIO 1.x")
      else if versions.exists(isZio200PreRelease) then Some("uses ZIO 2.0.0 milestone / RC")
      else None

  private def buildSourceFiles(projectDir: os.Path): List[os.Path] =
    val rootFiles = List(
      projectDir / "build.sbt",
      projectDir / "build.sc",
      projectDir / "build.scala",
      projectDir / "build.mill",
      projectDir / "Dependencies.scala",
      projectDir / "project" / "Dependencies.scala",
      projectDir / "project" / "deps.scala",
      projectDir / "project" / "Deps.scala"
    )
    val projectDirFiles =
      val dir = projectDir / "project"
      if os.exists(dir) && os.isDir(dir) then
        os.list(dir).filter(p => os.isFile(p) && (p.ext == "scala" || p.ext == "sbt")).toList
      else Nil
    (rootFiles ++ projectDirFiles).filter(os.isFile).distinct

  private def isZio1(version: String): Boolean =
    val v = version.trim
    SemVersion.unapply(v).exists(_.major == 1) || v.startsWith("1.")

  /** ZIO 2.0.0 pre-releases (e.g. `2.0.0-M5`, `2.0.0-RC5`) — do not force-upgrade to 2.1.x. */
  private def isZio200PreRelease(version: String): Boolean =
    version.trim.matches(Zio200PreRelease.regex)

  private def needsForceUpgrade(version: String): Boolean =
    if isZio1(version) || isZio200PreRelease(version) then false
    else
      SemVersion.unapply(version) match
        case Some(v) => v < ForceSemVersion
        case None    => false

  private def reachableCoreDeps(startDeps: List[ZioDep]): List[ZioDep] =
    val queue = mutable.Queue.from(startDeps)
    val visited = mutable.Set.empty[(String, String)]
    val reachable = mutable.ArrayBuffer.empty[ZioDep]
    while queue.nonEmpty do
      val dep = queue.dequeue()
      val key = dep.artifactId -> dep.version
      if visited.add(key) then
        if CoreArtifacts.contains(dep.logicalName) then reachable += dep
        loadPublishedDevZioDeps(dep.artifactId, dep.version).foreach(queue.enqueue(_))
    reachable.toList.distinctBy(dep => dep.logicalName -> dep.version)

  private def loadDirectZioMavenDeps(project: ProjectVersion): List[ZioDep] =
    val artifactsUrl =
      s"https://index.scala-lang.org/api/v1/projects/${project.p.organization}/${project.p.repository}/artifacts?stable-only=false"
    val artifactsJson = httpGet(artifactsUrl)
    val artifacts = ujson.read(artifactsJson).arr.toList
    val matching =
      artifacts.filter(a => a.obj.get("version").exists(_.str == project.v)) match
        case Nil =>
          artifacts.filter: a =>
            val id = a.obj.get("artifactId").map(_.str).getOrElse("")
            id.endsWith("_3") || a.obj.get("language").exists(_.str == "3")
        case matched => matched
    val artOpt = matching
      .sortBy: a =>
        val id = a.obj.get("artifactId").map(_.str).getOrElse("")
        if id.endsWith("_3") then 0 else 1
      .headOption

    artOpt match
      case None => Nil
      case Some(art) =>
        val groupId = art("groupId").str
        val artifactId = art("artifactId").str
        val tryVersions =
          List(project.v, art.obj.get("version").map(_.str).getOrElse(project.v)).distinct
        tryVersions.view
          .flatMap: version =>
            try Some(loadPomDevZioDeps(groupId, artifactId, version))
            catch case _: Exception => None
          .headOption
          .getOrElse(Nil)

  private def loadPublishedDevZioDeps(artifactId: String, version: String): List[ZioDep] =
    try loadPomDevZioDeps("dev.zio", artifactId, version)
    catch
      case ex: Exception =>
        Console.err.println(
          s"Failed to inspect transitive dev.zio deps for $artifactId:$version: $ex"
        )
        Nil

  private def loadPomDevZioDeps(groupId: String, artifactId: String, version: String): List[ZioDep] =
    DevZioPomDepsCache.getOrElseUpdate(
      (groupId, artifactId, version),
      readDevZioDepsFromPom(
        httpGet(
          s"https://repo1.maven.org/maven2/${groupId.replace('.', '/')}/$artifactId/$version/$artifactId-$version.pom"
        ),
        ownerVersion = version
      )
    )

  private def readDevZioDepsFromPom(pom: String, ownerVersion: String): List[ZioDep] =
    val doc = Jsoup.parse(pom, "", org.jsoup.parser.Parser.xmlParser())
    val properties = pomProperties(doc, ownerVersion)
    val managedVersions = pomManagedVersions(doc, properties, ownerVersion)
    doc
      .select("project > dependencies > dependency")
      .asScala
      .toList
      .flatMap: dep =>
        val group = Option(dep.selectFirst("groupId")).map(_.text.trim).getOrElse("")
        val artifactId = Option(dep.selectFirst("artifactId")).map(_.text.trim).getOrElse("")
        val rawVersion = Option(dep.selectFirst("version")).map(_.text.trim).getOrElse("")
        val version =
          resolvePomValue(rawVersion, properties)
            .orElse(managedVersions.get(group -> artifactId))
            .orElse(Option.when(group == "dev.zio" && rawVersion.isEmpty)(ownerVersion))
            .getOrElse("")
        if group == "dev.zio" && artifactId.nonEmpty && version.nonEmpty then
          Some(ZioDep(DependencyOverride.stripScalaBinarySuffix(artifactId), artifactId, version))
        else None
      .distinctBy(dep => dep.artifactId -> dep.version)

  private def pomProperties(
      doc: org.jsoup.nodes.Document,
      ownerVersion: String
  ): Map[String, String] =
    val fromPom =
      Option(doc.selectFirst("project > properties"))
        .toList
        .flatMap(_.children().asScala)
        .map(el => el.tagName() -> el.text.trim)
        .toMap
    fromPom ++ Map(
      "project.version" -> ownerVersion,
      "pom.version" -> ownerVersion,
    )

  private def pomManagedVersions(
      doc: org.jsoup.nodes.Document,
      properties: Map[String, String],
      ownerVersion: String
  ): Map[(String, String), String] =
    doc
      .select("project > dependencyManagement > dependencies > dependency")
      .asScala
      .toList
      .flatMap: dep =>
        val group = Option(dep.selectFirst("groupId")).map(_.text.trim).getOrElse("")
        val artifactId = Option(dep.selectFirst("artifactId")).map(_.text.trim).getOrElse("")
        val rawVersion = Option(dep.selectFirst("version")).map(_.text.trim).getOrElse("")
        resolvePomValue(rawVersion, properties)
          .orElse(Option.when(group == "dev.zio" && rawVersion.isEmpty)(ownerVersion))
          .map(version => (group -> artifactId) -> version)
      .toMap

  private def resolvePomValue(
      rawValue: String,
      properties: Map[String, String]
  ): Option[String] =
    val trimmed = rawValue.trim
    if trimmed.isEmpty then None
    else
      @annotation.tailrec
      def loop(current: String, remaining: Int): String =
        if remaining <= 0 then current
        else
          val replaced =
            Placeholder.replaceAllIn(current, m => properties.getOrElse(m.group(1), m.matched))
          if replaced == current then current else loop(replaced, remaining - 1)

      val resolved = loop(trimmed, remaining = 6)
      Option.when(resolved.nonEmpty && Placeholder.findFirstIn(resolved).isEmpty)(resolved)

  private def httpGet(url: String): String =
    import java.net.URI
    import java.net.http.{HttpClient, HttpRequest, HttpResponse}
    import java.time.Duration
    val client = HttpClient
      .newBuilder()
      .connectTimeout(Duration.ofSeconds(30))
      .build()
    val request = HttpRequest
      .newBuilder(URI.create(url))
      .timeout(Duration.ofSeconds(60))
      .header("User-Agent", "scala3-community-build")
      .GET()
      .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    if response.statusCode() != 200 then
      throw RuntimeException(s"HTTP ${response.statusCode()} for $url")
    response.body()
