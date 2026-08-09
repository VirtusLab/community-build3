import scala.jdk.CollectionConverters.*
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import org.jsoup.Jsoup

/** Discover ZIO core modules that need a force-upgrade for Scala 3.10 Tracer fixes.
  * Skips ZIO 1.x and 2.0.0-RCx projects entirely. Only emits overrides when current
  * version &lt; [[ForceVersion]]. Upgrading a 2.0.0-RCx project is API-breaking (`ZEnv`
  * removal, `unsafeRun` changes), so those are filtered out of the build instead.
  */
object ZioDependencyOverrideDiscovery:
  val ForceVersion = "2.1.26"
  private val ForceSemVersion = SemVersion(2, 1, 26)

  /** Bump when discovery skip/force rules change so [[ProjectBuildDefCache]] invalidates. */
  val DiscoveryRulesVersion = "1.1"

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

  private val Zio200Rc = raw"(?i)2\.0\.0-RC\d+.*".r
  private val QuotedVersion = raw""""(2\.\d+\.\d+[^"]*)"""".r
  private val ZioVersionAssign =
    raw"""(?i)zio(?:Version|Ver|_version)\s*=\s*"([^"]+)"""".r
  /** sbt: `"dev.zio" %% "zio-http" % "3.0.0-RC4"` (also `%` / `%%%`). */
  private val DevZioSbtDep =
    raw""""dev\.zio"\s*%{1,3}\s*"([^"]+)"\s*%\s*"([^"]+)"""".r
  /** Mill/ivy/scala-cli: `ivy"dev.zio::zio-http:3.0.0-RC4"`, `//> using dep "dev.zio::zio:2.1.14"`. */
  private val DevZioIvyDep =
    raw"""dev\.zio:{1,2}([A-Za-z0-9_.-]+):([^`"\s]+)""".r
  private val Placeholder = """\$\{([^}]+)\}""".r

  private case class ZioDep(logicalName: String, artifactId: String, version: String)

  private val DevZioPomDepsCache = TrieMap.empty[(String, String, String), List[ZioDep]]

  def discover(project: ProjectVersion, projectDir: os.Path): List[DependencyOverride] =
    try
      val sourceVersions = extractSourceZioVersions(projectDir)
      val sourceDeclaredDeps = extractSourceDevZioLibraryDeps(projectDir)
      // Only core modules share the ZIO version line. Independent `dev.zio` libs
      // (zio-prelude 1.x, zio-schema 1.x, zio-http 3.x) must not trigger ZIO 1.x / 2.0.0-RC skips.
      val sourceCoreVersions =
        sourceVersions ++ sourceDeclaredDeps.collect {
          case d if CoreArtifacts.contains(d.logicalName) => d.version
        }
      sourceSkipReason(sourceCoreVersions) match
        case Some(reason) =>
          println(
            s"Skipping ZIO dependencyOverrides for ${project.p.coordinates}: $reason (from sources)"
          )
          Nil
        case None =>
          val coreDeps = loadCoreDeps(project, sourceVersions, sourceDeclaredDeps)
          if coreDeps.isEmpty then Nil
          else if coreDeps.exists(dep => isZio1(dep.version)) then
            println(
              s"Skipping ZIO dependencyOverrides for ${project.p.coordinates}: uses ZIO 1.x (${coreDeps
                  .map(dep => s"${dep.logicalName}:${dep.version}")
                  .mkString(", ")})"
            )
            Nil
          else if coreDeps.exists(dep => isZio200Rc(dep.version)) then
            println(
              s"Skipping ZIO dependencyOverrides for ${project.p.coordinates}: uses ZIO 2.0.0-RC (${coreDeps
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

  /** Scaladex+Maven first; fall back to source-declared `dev.zio` deps (and their POMs).
    * Source fallback matters when Scaladex is down/offline and the project only depends on
    * non-core modules like `zio-http` (e.g. guinep) — core ZIO versions live in those POMs.
    */
  private def loadCoreDeps(
      project: ProjectVersion,
      sourceVersions: List[String],
      sourceDeclaredDeps: List[ZioDep]
  ): List[ZioDep] =
    val fromMaven =
      try
        val directZioDeps = loadDirectZioMavenDeps(project)
        if directZioDeps.isEmpty then Nil else reachableCoreDeps(directZioDeps)
      catch
        case ex: Exception =>
          Console.err.println(
            s"Maven/Scaladex ZIO discovery failed for ${project.p.coordinates}, trying sources: $ex"
          )
          Nil
    if fromMaven.nonEmpty then fromMaven
    else if sourceDeclaredDeps.nonEmpty then
      println(
        s"Using source-declared dev.zio deps for ${project.p.coordinates}: ${sourceDeclaredDeps
            .map(d => s"${d.logicalName}:${d.version}")
            .mkString(", ")}"
      )
      reachableCoreDeps(sourceDeclaredDeps)
    else
      val upgradeable = sourceVersions.filter(needsForceUpgrade)
      if upgradeable.isEmpty then Nil
      else
        println(
          s"Using source-declared ZIO versions for ${project.p.coordinates}: ${upgradeable.mkString(", ")}"
        )
        upgradeable.map(v => ZioDep("zio", "zio_3", v))

  /** Prefer build sources over Maven for skip decisions: community-build compiles the git checkout. */
  private def extractSourceZioVersions(projectDir: os.Path): List[String] =
    val text = buildSourceText(projectDir)
    if !text.contains("dev.zio") && !ZioVersionAssign.findFirstIn(text).isDefined then Nil
    else
      val assigned = ZioVersionAssign.findAllMatchIn(text).map(_.group(1)).toList
      val quoted =
        if text.contains("dev.zio") then
          QuotedVersion.findAllMatchIn(text).map(_.group(1)).toList
        else Nil
      (assigned ++ quoted).distinct

  /** Direct `dev.zio` libraryDependencies from build sources (sbt / Mill ivy / scala-cli). */
  private def extractSourceDevZioLibraryDeps(projectDir: os.Path): List[ZioDep] =
    val text = buildSourceText(projectDir)
    if !text.contains("dev.zio") then Nil
    else
      val fromSbt =
        DevZioSbtDep
          .findAllMatchIn(text)
          .map: m =>
            val logical = DependencyOverride.stripScalaBinarySuffix(m.group(1))
            ZioDep(logical, s"${logical}_3", m.group(2))
          .toList
      val fromIvy =
        DevZioIvyDep
          .findAllMatchIn(text)
          .map: m =>
            val logical = DependencyOverride.stripScalaBinarySuffix(m.group(1))
            ZioDep(logical, s"${logical}_3", m.group(2))
          .toList
      (fromSbt ++ fromIvy).distinctBy(d => d.logicalName -> d.version)

  private def buildSourceText(projectDir: os.Path): String =
    buildSourceFiles(projectDir)
      .flatMap(path => util.Try(os.read(path)).toOption)
      .mkString("\n")

  private def sourceSkipReason(versions: List[String]): Option[String] =
    if versions.isEmpty then None
    else if versions.exists(isZio1) then Some("uses ZIO 1.x")
    else if versions.exists(isZio200Rc) then Some("uses ZIO 2.0.0-RC")
    else None

  private def buildSourceFiles(projectDir: os.Path): List[os.Path] =
    val rootFiles = List(
      projectDir / "build.sbt",
      projectDir / "build.sc",
      projectDir / "build.scala",
      projectDir / "build.mill",
      projectDir / "build.mill.scala",
      projectDir / "Dependencies.scala",
      projectDir / "project" / "Dependencies.scala",
      projectDir / "project" / "deps.scala",
      projectDir / "project" / "Deps.scala"
    )
    def scalaFilesUnder(dir: os.Path): List[os.Path] =
      if os.exists(dir) && os.isDir(dir) then
        os.walk(dir, maxDepth = 3)
          .filter(p =>
            os.isFile(p) && (p.ext == "scala" || p.ext == "sbt" || p.ext == "sc" || p.last
              .endsWith(".mill"))
          )
          .toList
      else Nil
    val projectDirFiles = scalaFilesUnder(projectDir / "project")
    val millBuildFiles = scalaFilesUnder(projectDir / "mill-build")
    (rootFiles ++ projectDirFiles ++ millBuildFiles ++ scalaCliDirectiveFiles(projectDir))
      .filter(os.isFile)
      .distinct

  /** scala-cli projects declare dependencies in `//> using dep` directives instead of a build
    * file. They conventionally live in `project.scala`, but any source file may carry them.
    */
  private def scalaCliDirectiveFiles(projectDir: os.Path): List[os.Path] =
    util
      .Try(
        os.walk(projectDir, maxDepth = 2, skip = p => os.isDir(p) && p.last.startsWith("."))
          .filter(p => os.isFile(p) && (p.ext == "scala" || p.ext == "sc" || p.ext == "java"))
          .filter(p => util.Try(os.read(p)).toOption.exists(_.contains("//> using")))
          .toList
      )
      .getOrElse(Nil)

  private def isZio1(version: String): Boolean =
    val v = version.trim
    SemVersion.unapply(v).exists(_.major == 1) || v.startsWith("1.")

  /** ZIO 2.0.0 release candidates (e.g. `2.0.0-RC5`) — do not force-upgrade to 2.1.x. */
  private def isZio200Rc(version: String): Boolean =
    version.trim.matches(Zio200Rc.regex)

  private def needsForceUpgrade(version: String): Boolean =
    if isZio1(version) || isZio200Rc(version) then false
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
    val artifactsJson = httpGet(artifactsUrl).get
    val artifacts = ujson.read(artifactsJson).arr.toList
    val matching =
      artifacts.filter(a => a.obj.get("version").exists(_.str == project.v)) match
        case Nil =>
          artifacts.filter: a =>
            val id = a.obj.get("artifactId").map(_.str).getOrElse("")
            id.endsWith("_3") || a.obj.get("language").exists(_.str == "3")
        case matched => matched
    val candidateArtifacts = matching
      .sortBy: a =>
        val id = a.obj.get("artifactId").map(_.str).getOrElse("")
        if id.endsWith("_3") then 0 else 1
    // Inspect all published Scala 3 artifacts for the project version. Some projects,
    // like guinep, use ZIO only from a secondary module (e.g. `guinep-web` via zio-http),
    // and looking at a single artifact misses the transitive `dev.zio` graph entirely.
    candidateArtifacts
      .flatMap: art =>
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
      .distinctBy(dep => dep.artifactId -> dep.version)

  private def loadPublishedDevZioDeps(artifactId: String, version: String): List[ZioDep] =
    try loadPomDevZioDeps("dev.zio", artifactId, version)
    catch
      case ex: Exception =>
        // Non-core modules (zio-http, zio-json, ...) may lack a `_3` build for every version;
        // try the binary-suffix-free coordinates used by some early releases.
        if artifactId.endsWith("_3") then
          val unsuffixed = artifactId.stripSuffix("_3")
          try loadPomDevZioDeps("dev.zio", unsuffixed, version)
          catch
            case ex2: Exception =>
              Console.err.println(
                s"Failed to inspect transitive dev.zio deps for $artifactId:$version: $ex2"
              )
              Nil
        else
          Console.err.println(
            s"Failed to inspect transitive dev.zio deps for $artifactId:$version: $ex"
          )
          Nil

  private def loadPomDevZioDeps(groupId: String, artifactId: String, version: String): List[ZioDep] =
    DevZioPomDepsCache.getOrElseUpdate(
      (groupId, artifactId, version),
      httpGet(
        s"https://repo1.maven.org/maven2/${groupId.replace('.', '/')}/$artifactId/$version/$artifactId-$version.pom"
      ).map(readDevZioDepsFromPom(_, ownerVersion = version)).get
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

  private def httpGet(url: String): util.Try[String] =
    import sttp.client4.quick.*
    import scala.concurrent.duration.DurationInt
    util
      .Try:
        quickRequest
          .get(uri"$url")
          .header("User-Agent", "scala3-community-build")
          .readTimeout(60.seconds)
          .send()
      .flatMap: response =>
        if response.isSuccess then util.Success(response.body)
        else util.Failure(RuntimeException(s"HTTP ${response.code} for $url"))
