import com.typesafe.config.ConfigFactory
import pureconfig._
import pureconfig.error.*
import java.io.FileNotFoundException
import scala.util.chaining.*

class ProjectConfigDiscovery(internalProjectConfigsPath: java.io.File, requiredConfigsPath: os.Path) {
  type Filename = String
  def loadRequiredProjectsLists(dirPath: os.Path): Seq[(Project, Filename)] = 
    for 
      file <- os.list(dirPath)
      fileName = file.last
      project <- os.read.lines(file)
        .map(_.trim)
        .filter(_.nonEmpty)
        .map(Project.load)
    yield project -> fileName

  lazy val projectSourceVersions: Map[Project, String] = 
    loadRequiredProjectsLists(requiredConfigsPath / "source-version").toMap

  lazy val projectMigrationVersions: Map[Project, List[String]] = loadRequiredProjectsLists(requiredConfigsPath / "source-migration-rewrite")
    .groupMap(_._1)(_._2)
    .view
    .mapValues:
      _.distinct
      .map: version =>
        version -> version.split("\\W").toList.map: v =>
          v.toIntOption.getOrElse:
            v.dropWhile(!_.isDigit).takeWhile(_.isDigit).toIntOption.getOrElse(0)
      .pipe: versions => 
        // Ordering of List[Int] matches the semantic versioning if the vector of versions is normalized (all vectors have the same length)
        val longestSemVer = versions.map(_._2).map(_.length).max
        if longestSemVer == 3 then versions.toMap
        else versions.toMap.view.mapValues(_.padTo(longestSemVer, 0)).toMap
      .pipe: normalizedVersions => 
        val normalizedToVersionString = normalizedVersions.map((k,v) => (v, k))
        normalizedVersions.map(_._2).toList.map(normalizedToVersionString(_)).sorted
    .toMap

  lazy val projectTestsConfig: Map[Project, TestingMode] = loadRequiredProjectsLists(requiredConfigsPath / "tests")
    .map {
      case (project, "compile-only") => project -> TestingMode.CompileOnly
      case (project, "disabled") => project -> TestingMode.Disabled
      case (project, "full") => project -> TestingMode.Full
      case _ => ??? // unreachable
    }.toMap

  def apply(
      project: ProjectVersion,
      repoUrl: String,
      revision: Option[Git.Revision]
  ): Option[ProjectBuildConfig] = {
    val name = project.showName

    Git
      .checkout(repoUrl, name, revision, depth = Some(1))
      .flatMap { projectDir =>
        try {
          readProjectConfig(projectDir, repoUrl)
            .orElse(internalProjectConfigs(project.showName))
            .orElse(Some(ProjectBuildConfig.empty))
            .map: c =>
              if c.java.version.nonEmpty then c
              else c.copy(java = c.java.copy(version = discoverJavaVersion(projectDir)))
            .map: c =>
              c.copy(sourcePatches = c.sourcePatches ::: discoverSourcePatches(projectDir))
            .map: c =>
              if c.sourceVersion.isDefined then c
              else c.copy(sourceVersion = projectSourceVersions.get(project.p))  
            .map: c =>
              if c.migrationVersions.nonEmpty then c
              else c.copy(migrationVersions = projectMigrationVersions.getOrElse(project.p, Nil))
            .map: c =>
              if c.tests != TestingMode.default then c
              else projectTestsConfig.get(project.p).foldLeft(c){(c, value) => c.copy(tests = value)}
            .filter(_ != ProjectBuildConfig.empty)
        } catch {
          case ex: Throwable =>
            Console.err.println(
              s"Failed to resolve project config: ${ex}"
            )
            None
        } finally os.remove.all(projectDir)
      }
  }

  private def githubWorkflows(projectDir: os.Path) = {
    val workflowsDir = projectDir / ".github" / "workflows"
    if !os.exists(workflowsDir) then Nil
    else
      os.walk
        .stream(workflowsDir)
        .filter(file => file.ext.contains("yml") || file.ext.contains("yaml"))
        .toList
  }

  private def commonBuildFiles(projectDir: os.Path) = {
    val files = projectDir / "build.sc" ::
      projectDir / "build.scala" ::
      projectDir / "build.mill" ::
      projectDir / "build.sbt" ::
      List(projectDir / "project")
        .filter(os.exists(_))
        .flatMap(os.walk(_))
        .toList

    files.filter(f => os.exists(f) && os.isFile(f))
  }

  private lazy val referenceConfig =
    ConfigSource
      .resources("buildPlan.reference.conf")
      .cursor()
      .flatMap(_.asConfigValue)
      .toOption

  // Resolve project config from mainted, internal project configs list
  private def internalProjectConfigs(projectName: String) = {
    val fallbackConfig =
      referenceConfig.foldLeft(ConfigFactory.empty)(_.withValue(projectName, _))
    val config = ConfigSource
      .file(internalProjectConfigsPath)
      .withFallback(ConfigSource.fromConfig(fallbackConfig))
      .at(projectName.toLowerCase)
      .load[ProjectBuildConfig]

    config.left.foreach {
      case ConfigReaderFailures(
            CannotReadFile(file, Some(_: FileNotFoundException))
          ) =>
        System.err.println(s"Internal conifg projects not configured: $file")
      case ConfigReaderFailures(
            ConvertFailure(KeyNotFound(`projectName`, _), _, _)
          ) =>
        ()
      case failure =>
        sys.error(
          s"Failed to decode content of ${internalProjectConfigsPath}, reason: ${failure.prettyPrint(0)}"
        )
    }

    config.toOption
  }

  // Read project config defined withing the repo
  private def readProjectConfig(
      projectDir: os.Path,
      repoUrl: String
  ): Option[ProjectBuildConfig] = {
    val config = ConfigSource
      .file((projectDir / "scala3-community-build.conf").toIO)
      .withFallback(ConfigSource.resources("buildPlan.reference.conf"))
      .load[ProjectBuildConfig]

    config.left.foreach {
      case reasons: ConfigReaderFailures if reasons.toList.exists {
            case CannotReadFile(_, Some(_: java.io.FileNotFoundException)) =>
              true
            case _ => false
          } =>
        ()
      case reason =>
        sys.error(
          s"Failed to decode community-build config in ${repoUrl}, reason: ${reason}"
        )
    }
    config.toOption
  }

  // Discover java version to use based on the GitHub Actions workflows
  // Selects minimal supported version of Java used in the workflows
  private def discoverJavaVersion(projectDir: os.Path): Option[String] =
    val OptQuote = "['\"]?".r
    // java-version is used by setup-scala and setup-java actions
    // jvm is used by scala-cli action
    // release is used by oracle-actions/setup-java@v1
    val JavaVersion = raw"(?:java-version|jdk|jvm|release):\s*(.*)".r
    val JavaVersionNumber = raw"$OptQuote(\d+)$OptQuote".r
    val JavaVersionDistroVer =
      raw"$OptQuote(\w+)[@:]([\d\.]*[\w\-\_\.]*)$OptQuote".r
    val MatrixEntry = raw"(\w+):\s*\[(.*)\]".r
    // We can only supported this versions
    val allowedVersions = Seq(17, 21, 25)

    def isJavaVersionMatrixEntry(key: String): Boolean = {
      Set("java", "jdk", "jvm", "release").contains(key.toLowerCase)
    }
    githubWorkflows(projectDir)
      .flatMap { path =>
        tryReadLines(path)
          .map(_.trim)
          .flatMap {
            case MatrixEntry(key, valuesList) if isJavaVersionMatrixEntry(key) =>
              valuesList.split(",").map(_.trim)
            case JavaVersion(value) => Option(value)
            case _                  => Nil
          }
          .flatMap {
            case JavaVersionNumber(version) => Option(version)
            case JavaVersionDistroVer(distro, version) if !distro.contains("graal") =>
              version
                .stripPrefix("1.")
                .split('.')
                .headOption
            case _ => None
          }
          .flatMap(_.toIntOption)
      }
      .toList
      .distinct
      .flatMap(version => allowedVersions.find(_ >= version))
      .maxOption
      .map(_.toString)

  private def discoverSourcePatches(projectDir: os.Path): List[SourcePatch] =
    object Scala3VersionDef {
      case class Replecement(toMatch: String, replecement: String)
      final val scala3VersionNames = List(
        "Scala3", // https://github.com/zio/zio/blob/5e56f0e252477a3aef60140bd05ae4f20b4f8f39/project/BuildHelper.scala#L25
        "scala3", // https://github.com/ghostdogpr/caliban/blob/95c5bafac4b8c72e5eb2af9b52b6cb7554a7da2d/build.sbt#L6
        "ScalaDotty", // https://github.com/zio/zio-json/blob/f190390f8a69422d3c9bfb5b8e51c5214618efe9/project/BuildHelper.scala#L23
        "scalaDotty",
        "Scala3Version",
        "scala3Version", // https://github.com/47degrees/fetch/blob/c4732a827816c58ce84013e9580120bdc3f64bc6/build.sbt#L10
        "Scala_3", // https://github.dev/kubukoz/sup/blob/644848c03173c726f19a40e6dd439b6905d42967/build.sbt#L10-L11
        "scala_3",
        "`Scala-3`",
        "`scala-3`" // https://github.com/rolang/dumbo/blob/7cc7f22ee45632b45bb1092418a3498ede8226da/build.sbt#L3
      )
      val Scala3VersionNamesAlt = matchEnclosed(
        scala3VersionNames.mkString("|")
      )
      val DefOrVal = matchEnclosed("def|val|var")
      val OptType = s"${matchEnclosed(raw":\s*String")}?"
      val ScalaVersionDefn = s"$DefOrVal $Scala3VersionNamesAlt$OptType".r
      def matchEnclosed(pattern: String) = s"(?:$pattern)".r
      def defnPattern(rhsPattern: String) =
        raw".*(($ScalaVersionDefn)\s*=\s*($rhsPattern))\s*".r
      val Scala3Version = """"3\.\d+\.\d+\S*""""

      // https://github.com/ghostdogpr/caliban/blob/95c5bafac4b8c72e5eb2af9b52b6cb7554a7da2d/build.sbt#L6
      val StringVersionDefn = defnPattern(Scala3Version)
      // https://github.com/valskalla/odin/blob/db4444fe4efcb5c497d4e23bdf9bbcffd27269c2/build.sbt#L24
      val VersionSeq = raw"""(Seq|Set|List|Vector)\((?:${Scala3Version},?)*\)"""
      val VersionsSeqDefn = defnPattern(VersionSeq)
      val VersionsSeqCondDefn = defnPattern(
        raw"""if\s*\(.*\) .* else ${VersionSeq}"""
      )
      // https://github.com/zio/zio/blob/39322c7b41b913cbadc44db7885cedc6c2505e08/project/BuildHelper.scala#L25
      val BinVersionSelector = defnPattern(raw"""\S+\("3\.?\S*"\)""")

      def unapply(line: String): Option[Replecement] =
        val scalaVersionStringStub = """"<SCALA_VERSION>""""
        val uncommentedLine = line.indexOf("//") match {
          case -1  => line
          case idx => line.substring(0, idx)
        }
        uncommentedLine.trim match {
          case StringVersionDefn(wholeDefn, definition, _) =>
            Some(
              Replecement(wholeDefn, s"$definition = ${scalaVersionStringStub}")
            )
          case VersionsSeqDefn(wholeDefn, definition, _, seqType) =>
            Some(
              Replecement(
                wholeDefn,
                s"$definition = $seqType(${scalaVersionStringStub})"
              )
            )
          case VersionsSeqCondDefn(wholeDefn, definition, _, seqType) =>
            Some(
              Replecement(
                wholeDefn,
                s"$definition = $seqType(${scalaVersionStringStub})"
              )
            )
          case BinVersionSelector(wholeDefn, definition, _) =>
            Some(
              Replecement(wholeDefn, s"$definition = ${scalaVersionStringStub}")
            )
          case _ => None
        }
    }

    commonBuildFiles(projectDir).flatMap { file =>
      def patch(pattern: String, replacement: String) = SourcePatch(
        path = file.relativeTo(projectDir).toString,
        pattern = pattern,
        replaceWith = replacement
      )
      tryReadLines(file).collect {
        case Scala3VersionDef(toMatch, replecement) =>
          patch(pattern = toMatch, replacement = replecement)
        case line @ s"ThisBuild${_}/${_}tlFatalWarnings${_}:=${_}true" =>
          patch(pattern = line, replacement = "")
      }.distinct
    }
  end discoverSourcePatches

  private def tryReadLines(file: os.Path): Seq[String] = {
    try os.read.lines(file).toSeq
    catch {
      case ex: Exception =>
        println(s"Failed to read $file, reason: $ex")
        Nil
    }
  }
}
