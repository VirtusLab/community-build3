import scala.jdk.CollectionConverters.*
import scala.collection.concurrent.TrieMap
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
  val DiscoveryRulesVersion = "1.4"

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
  private val ZioVersionAssign =
    raw"""(?i)zio(?:Version|Ver|_version)\s*=\s*"([^"]+)"""".r
  /** Any `val NAME = "..."` assignment, used to resolve version vars referenced by `dev.zio` deps. */
  private val ValStringAssign =
    raw"""(?m)\b([A-Za-z_]\w*)\s*=\s*"([^"]+)"""".r
  /** sbt: `"dev.zio" %% "zio-http" % "3.0.0-RC4"` (also `%` / `%%%`). */
  private val DevZioSbtDep =
    raw""""dev\.zio"\s*%{1,3}\s*"([^"]+)"\s*%\s*"([^"]+)"""".r
  /** sbt with a variable version: `"dev.zio" %% "zio-test" % ZioVersion`, resolved via [[ValStringAssign]]. */
  private val DevZioSbtDepVar =
    raw""""dev\.zio"\s*%{1,3}\s*"([^"]+)"\s*%\s*([A-Za-z_]\w*)""".r
  /** Mill/ivy/scala-cli: `ivy"dev.zio::zio-http:3.0.0-RC4"`, `//> using dep "dev.zio::zio:2.1.14"`. */
  private val DevZioIvyDep =
    raw"""dev\.zio:{1,2}([A-Za-z0-9_.-]+):([^`"\s]+)""".r
  private val Placeholder = """\$\{([^}]+)\}""".r

  private case class ZioDep(logicalName: String, artifactId: String, version: String)
  private case class MavenCoord(groupId: String, artifactId: String, version: String)

  private enum UpgradeDecision:
    case Skip(reason: String)
    case NoOverride
    case ForceUpgrade

  private val DevZioPomDepsCache = TrieMap.empty[(String, String, String), List[ZioDep]]
  private val PomDirectDepsCache = TrieMap.empty[(String, String, String), List[MavenCoord]]
  private val PomHttpTimeoutMs = 15_000

  /** Groups that never carry a meaningful `dev.zio` core dep for our purposes. */
  private val IgnoredCarrierGroups: Set[String] = Set(
    "org.scala-lang",
    "org.scala-lang.modules",
    "org.scala-js",
    "org.scala-native",
  )

  def discover(
      project: ProjectVersion,
      projectDir: os.Path,
      buildConfigSeed: BuildConfigSeedIndex
  ): List[DependencyOverride] =
    try
      val sourceText = buildSourceText(projectDir)
      val sourceVersions = extractSourceZioVersions(sourceText)
      val sourceDeclaredDeps = extractSourceDevZioLibraryDeps(sourceText)
      // Only core modules share the ZIO version line. Independent `dev.zio` libs
      // (zio-prelude 1.x, zio-schema 1.x, zio-http 3.x) must not trigger ZIO 1.x / 2.0.0-RC skips.
      val sourceCoreVersions =
        sourceVersions ++ sourceDeclaredDeps.collect {
          case d if CoreArtifacts.contains(d.logicalName) => d.version
        }
      // Fast path: sources already name a core ZIO version — no Maven POM walk.
      decideFromVersions(sourceCoreVersions) match
        case Some(decision) =>
          applyDecision(project, decision, origin = "sources")
        case None =>
          val pomVersions =
            coreVersionsFromPomFrontier(project, sourceVersions, sourceDeclaredDeps, buildConfigSeed)
          decideFromVersions(pomVersions) match
            case Some(decision) =>
              applyDecision(project, decision, origin = "Maven POMs")
            case None => Nil
    catch
      case ex: Exception =>
        Console.err.println(
          s"Failed to discover ZIO dependencyOverrides for ${project.p.coordinates}: $ex"
        )
        Nil

  private def forceOverrides: List[DependencyOverride] =
    // Force the full core set once any reachable core dep needs it, even when
    // the project only declares a downstream/non-core `dev.zio` module directly.
    CoreArtifacts.toList
      .map(artifact => DependencyOverride.scala("dev.zio", artifact, ForceVersion))
      .distinctBy(_.moduleKey)

  private def applyDecision(
      project: ProjectVersion,
      decision: UpgradeDecision,
      origin: String
  ): List[DependencyOverride] =
    decision match
      case UpgradeDecision.Skip(reason) =>
        println(
          s"Skipping ZIO dependencyOverrides for ${project.p.coordinates}: $reason (from $origin)"
        )
        Nil
      case UpgradeDecision.NoOverride => Nil
      case UpgradeDecision.ForceUpgrade =>
        println(
          s"Forcing ZIO $ForceVersion dependencyOverrides for ${project.p.coordinates} (from $origin)"
        )
        forceOverrides

  /** `None` = not enough version signal; otherwise skip / no-op / force. */
  private def decideFromVersions(versions: List[String]): Option[UpgradeDecision] =
    if versions.isEmpty then None
    else if versions.exists(isZio1) then Some(UpgradeDecision.Skip("uses ZIO 1.x"))
    else if versions.exists(isZio200Rc) then Some(UpgradeDecision.Skip("uses ZIO 2.0.0-RC"))
    else if versions.exists(needsForceUpgrade) then Some(UpgradeDecision.ForceUpgrade)
    else Some(UpgradeDecision.NoOverride)

  /** Shallow POM inspection only — never BFS the full ZIO graph.
    * Non-core `dev.zio` deps (e.g. zio-http) are fetched once; their direct core deps decide.
    * If the project POM has no direct `dev.zio` at all (e.g. ZIO only via
    * `com.thesamet.scalapb.zio-grpc:zio-grpc-core`), peek one hop into those carriers.
    */
  private def coreVersionsFromPomFrontier(
      project: ProjectVersion,
      sourceVersions: List[String],
      sourceDeclaredDeps: List[ZioDep],
      buildConfigSeed: BuildConfigSeedIndex
  ): List[String] =
    val startDeps =
      if sourceDeclaredDeps.nonEmpty then
        println(
          s"Using source-declared dev.zio deps for ${project.p.coordinates}: ${sourceDeclaredDeps
              .map(d => s"${d.logicalName}:${d.version}")
              .mkString(", ")}"
        )
        sourceDeclaredDeps
      else
        try loadDirectZioMavenDeps(project, buildConfigSeed)
        catch
          case ex: Exception =>
            Console.err.println(
              s"Maven ZIO discovery failed for ${project.p.coordinates}: $ex"
            )
            Nil
    val fromStartDeps =
      if startDeps.isEmpty then Nil
      else
        startDeps
          .flatMap: dep =>
            if CoreArtifacts.contains(dep.logicalName) then List(dep.version)
            else
              loadPublishedDevZioDeps(dep.artifactId, dep.version).collect:
                case d if CoreArtifacts.contains(d.logicalName) => d.version
          .distinct
    if fromStartDeps.nonEmpty then fromStartDeps
    else
      val fromCarriers =
        try loadCoreVersionsViaNonZioCarriers(project, buildConfigSeed)
        catch
          case ex: Exception =>
            Console.err.println(
              s"Maven ZIO carrier discovery failed for ${project.p.coordinates}: $ex"
            )
            Nil
      if fromCarriers.nonEmpty then fromCarriers
      else sourceVersions.filter(needsForceUpgrade)

  /** One-hop peek: project POM → non-`dev.zio` direct deps → their `dev.zio` core versions.
    * Covers libraries that depend on ZIO only through ecosystem carriers (zio-grpc, etc.).
    */
  private def loadCoreVersionsViaNonZioCarriers(
      project: ProjectVersion,
      buildConfigSeed: BuildConfigSeedIndex
  ): List[String] =
    val carriers = loadDirectProjectMavenDeps(project, buildConfigSeed)
      .filterNot: dep =>
        dep.groupId == "dev.zio" || IgnoredCarrierGroups.contains(dep.groupId)
    if carriers.isEmpty then Nil
    else
      println(
        s"Inspecting non-dev.zio ZIO carriers for ${project.p.coordinates}: ${carriers
            .map(d => s"${d.groupId}:${d.artifactId}:${d.version}")
            .mkString(", ")}"
      )
      carriers
        .flatMap: dep =>
          try
            loadPomDevZioDeps(dep.groupId, dep.artifactId, dep.version).collect:
              case d if CoreArtifacts.contains(d.logicalName) => d.version
          catch case _: Exception => Nil
        .distinct

  /** Prefer build sources over Maven for skip decisions: community-build compiles the git checkout.
    *
    * Only versions explicitly assigned to a ZIO-named variable (`zioVersion = "…"`) count here.
    * A blanket scan of every quoted `2.x` literal is unsound: unrelated `dev.zio`-adjacent deps
    * (e.g. `ZHTTPVersion = "2.0.0-RC11"` for `io.d11 %% zhttp`) would masquerade as a ZIO core
    * version and trigger a spurious `2.0.0-RC` skip. Actual `dev.zio` core versions — literal or
    * variable-referenced — are picked up by [[extractSourceDevZioLibraryDeps]] instead.
    */
  private def extractSourceZioVersions(text: String): List[String] =
    ZioVersionAssign.findAllMatchIn(text).map(_.group(1)).toList.distinct

  /** Direct `dev.zio` libraryDependencies from build sources (sbt / Mill ivy / scala-cli). */
  private def extractSourceDevZioLibraryDeps(text: String): List[ZioDep] =
    if !text.contains("dev.zio") then Nil
    else
      def zioDep(artifact: String, version: String): ZioDep =
        val logical = DependencyOverride.stripScalaBinarySuffix(artifact)
        ZioDep(logical, s"${logical}_3", version)

      val fromSbt =
        DevZioSbtDep
          .findAllMatchIn(text)
          .map(m => zioDep(m.group(1), m.group(2)))
          .toList
      // Deps whose version is a `val` (e.g. `"dev.zio" %% "zio-test" % ZioVersion`); resolve the var.
      val valVersions = ValStringAssign.findAllMatchIn(text).map(m => m.group(1) -> m.group(2)).toMap
      val fromSbtVar =
        DevZioSbtDepVar
          .findAllMatchIn(text)
          .flatMap(m => valVersions.get(m.group(2)).map(version => zioDep(m.group(1), version)))
          .toList
      val fromIvy =
        DevZioIvyDep
          .findAllMatchIn(text)
          .map(m => zioDep(m.group(1), m.group(2)))
          .toList
      (fromSbt ++ fromSbtVar ++ fromIvy).distinctBy(d => d.logicalName -> d.version)

  private def buildSourceText(projectDir: os.Path): String =
    buildSourceFiles(projectDir)
      .flatMap(path => util.Try(os.read(path)).toOption)
      .mkString("\n")

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

  /** Inspect published module POMs from buildConfig `targets` for direct `dev.zio` deps. */
  private def loadDirectZioMavenDeps(
      project: ProjectVersion,
      buildConfigSeed: BuildConfigSeedIndex
  ): List[ZioDep] =
    projectPomCandidates(project, buildConfigSeed)
      .flatMap: (groupId, artifactId) =>
        try loadPomDevZioDeps(groupId, artifactId, project.v)
        catch case _: Exception => Nil
      .distinctBy(dep => dep.artifactId -> dep.version)
      .toList

  /** All direct Maven deps declared by the project's published Scala 3 artifacts. */
  private def loadDirectProjectMavenDeps(
      project: ProjectVersion,
      buildConfigSeed: BuildConfigSeedIndex
  ): List[MavenCoord] =
    projectPomCandidates(project, buildConfigSeed)
      .flatMap: (groupId, artifactId) =>
        try loadPomDirectDeps(groupId, artifactId, project.v)
        catch case _: Exception => Nil
      .distinctBy(dep => (dep.groupId, dep.artifactId, dep.version))
      .toList

  private def projectPomCandidates(
      project: ProjectVersion,
      buildConfigSeed: BuildConfigSeedIndex
  ): List[(String, String)] =
    // Inspect all known Scala 3 artifacts for the project version. Some projects,
    // like guinep, use ZIO only from a secondary module (e.g. `guinep-web` via zio-http),
    // and looking at a single artifact misses the transitive `dev.zio` graph entirely.
    buildConfigSeed
      .targets(project.p)
      .map: (groupId, artifact) =>
        val module =
          if artifact.endsWith("_3") then artifact.stripSuffix("_3") else artifact
        (groupId, Maven.scalaArtifactId(module, "3"))
      .distinct
      .toList

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
      httpGet(Maven.pomUrl(groupId, artifactId, version))
        .map(readDevZioDepsFromPom(_, ownerVersion = version))
        .get
    )

  private def loadPomDirectDeps(groupId: String, artifactId: String, version: String): List[MavenCoord] =
    PomDirectDepsCache.getOrElseUpdate(
      (groupId, artifactId, version),
      httpGet(Maven.pomUrl(groupId, artifactId, version))
        .map(readDirectDepsFromPom(_, ownerVersion = version))
        .get
    )

  private def readDirectDepsFromPom(pom: String, ownerVersion: String): List[MavenCoord] =
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
        if group.nonEmpty && artifactId.nonEmpty && version.nonEmpty then
          Some(MavenCoord(group, artifactId, version))
        else None
      .distinctBy(dep => (dep.groupId, dep.artifactId, dep.version))

  private def readDevZioDepsFromPom(pom: String, ownerVersion: String): List[ZioDep] =
    readDirectDepsFromPom(pom, ownerVersion)
      .collect:
        case MavenCoord("dev.zio", artifactId, version) =>
          ZioDep(DependencyOverride.stripScalaBinarySuffix(artifactId), artifactId, version)
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
    util.Try:
      Jsoup
        .connect(url)
        .ignoreContentType(true)
        .userAgent("scala3-community-build")
        .timeout(PomHttpTimeoutMs)
        .execute()
        .body()
