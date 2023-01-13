import com.typesafe.config.ConfigFactory
import pureconfig._
import pureconfig.error.*
import java.io.FileNotFoundException
import scala.util.Try
import scala.annotation.tailrec

class ProjectConfigDiscovery(internalProjectConfigsPath: java.io.File) {
  def apply(
      project: ProjectVersion,
      repoUrl: String,
      tagOrRevision: Option[String]
  ): Option[ProjectBuildConfig] = {
    val name = project.showName

    checkout(repoUrl, name, tagOrRevision)
      .flatMap { projectDir =>
        try {
          readProjectConfig(projectDir, repoUrl)
            .orElse(internalProjectConfigs(project.showName))
            .orElse(Some(ProjectBuildConfig.empty))
            .map { c =>
              if c.java.version.nonEmpty then c
              else c.copy(java = c.java.copy(version = discoverJavaVersion(projectDir)))
            }
            .map { c =>
              val default = ProjectBuildConfig.defaultMemoryRequest
              if c.memoryRequestMb > default then c
              else
                val discovered = discoverMemoryRequest(projectDir)
                  .filter(_ > default)
                  .map(_ min 8192)
                  .getOrElse(default)
                c.copy(memoryRequestMb = discovered)
            }
            .map { c =>
              c.copy(sourcePatches = c.sourcePatches ::: discoverSourcePatches(projectDir))
            }
            .filter(_ != ProjectBuildConfig.empty)
        } catch {
          case ex: Throwable =>
            Console.err.println(
              s"Failed to resolve project config: ${ex.getMessage}"
            )
            None
        } finally os.remove.all(projectDir)
      }
  }

  private def checkout(
      repoUrl: String,
      projectName: String,
      tagOrRevision: Option[String]
  ): Option[os.Path] = {
    @tailrec def retry[T](
        retries: Int,
        backoffSeconds: Int = 1
    ): Option[os.Path] = {
      val projectDir = os.temp.dir(prefix = s"repo-$projectName")
      val proc = os
        .proc(
          "git",
          "clone",
          repoUrl,
          projectDir,
          "--quiet",
          tagOrRevision.map("--branch=" + _).toList,
          "--depth=1"
        )
        .call(stderr = os.Pipe, check = false)

      if proc.exitCode == 0 then Some(projectDir)
      else if retries > 0 then
        Console.err.println(
          s"Failed to checkout $repoUrl at revision ${tagOrRevision}, backoff ${backoffSeconds}s"
        )
        proc.err.lines().foreach(Console.err.println)
        Thread.sleep(backoffSeconds * 1000)
        retry(retries - 1, (backoffSeconds * 2).min(60))
      else
        Console.err.println(
          s"Failed to checkout $repoUrl at revision ${tagOrRevision}:"
        )
        proc.err.lines().foreach(Console.err.println)
        None
    }
    retry(retries = 10)
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
      .at(projectName)
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
        throw new RuntimeException(
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
        System.err.println(
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
    val JavaVersionDistroVer = raw"$OptQuote(\w+)[@:]([\d\.]*[\w\-\_\.]*)$OptQuote".r
    val MatrixEntry = raw"(\w+):\s*\[(.*)\]".r
    // We can only supported this versions
    val allowedVersions = Seq(8, 11, 17, 19)

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
            case other => None
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
        "scala_3"
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
          case StringVersionDefn(wholeDefn, definition, value) =>
            Some(
              Replecement(wholeDefn, s"$definition = ${scalaVersionStringStub}")
            )
          case VersionsSeqDefn(wholeDefn, definition, value, seqType) =>
            Some(
              Replecement(
                wholeDefn,
                s"$definition = $seqType(${scalaVersionStringStub})"
              )
            )
          case VersionsSeqCondDefn(wholeDefn, definition, value, seqType) =>
            Some(
              Replecement(
                wholeDefn,
                s"$definition = $seqType(${scalaVersionStringStub})"
              )
            )
          case BinVersionSelector(wholeDefn, definition, value) =>
            Some(
              Replecement(wholeDefn, s"$definition = ${scalaVersionStringStub}")
            )
          case _ => None
        }
    }
    def scala3VersionDefs =
      commonBuildFiles(projectDir).flatMap { file =>
        import Scala3VersionDef.Replecement
        tryReadLines(file).collect { case Scala3VersionDef(toMatch, replecement) =>
          SourcePatch(
            path = file.relativeTo(projectDir).toString,
            pattern = toMatch,
            replaceWith = replecement
          )
        }
      }
    end scala3VersionDefs
    scala3VersionDefs
  end discoverSourcePatches

  private def tryReadLines(file: os.Path): Seq[String] = {
    try os.read.lines(file).toSeq
    catch {
      case ex: Exception =>
        println(s"Failed to read $file, reason: $ex")
        Nil
    }
  }

  // Selects recommened amount of memory for project pod, based on the config files
  private def discoverMemoryRequest(projectDir: os.Path): Option[Int] =
    def readXmx(file: os.Path): Seq[Xmx.MegaBytes] =
      tryReadLines(file).collect { case Xmx(mBytes) => mBytes }

    val fromWorkflows = githubWorkflows(projectDir)
      .flatMap(readXmx)

    val fromOptsFiles = os
      .list(projectDir)
      .filter(
        _.lastOpt.exists { name =>
          name.contains("sbtopts") || name.contains("jvmopts") ||
          name.contains("jvm-opts")
        }
      )
      .flatMap(readXmx)

    val fromBuild = commonBuildFiles(projectDir).flatMap(readXmx)

    val allCandidates = fromOptsFiles ++ fromWorkflows ++ fromBuild
    allCandidates.maxOption
  end discoverMemoryRequest

  private object Xmx {
    type MegaBytes = Int
    final val XmxPattern = raw".*-Xmx(\d+)(\w)?.*".r

    def unapply(text: String): Option[MegaBytes] =
      def commentStartIdx = text.indexOf("#") max text.indexOf("//")
      def isNotCommented =
        commentStartIdx < 0 || text.indexOf("-Xmx") < commentStartIdx
      text match {
        case XmxPattern(size, unit) if isNotCommented =>
          val sizeN = size.toInt
          val mbytes = unit match {
            case null      => sizeN / 1024 / 1024
            case "k" | "K" => sizeN / 1024
            case "m" | "M" => sizeN
            case "g" | "G" => sizeN * 1024
          }
          Some(mbytes)
        case nope => None
      }
  }
}
