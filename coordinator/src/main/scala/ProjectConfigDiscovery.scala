import com.typesafe.config.ConfigFactory
import pureconfig._
import pureconfig.error.*
import java.io.FileNotFoundException

class ProjectConfigDiscovery(internalProjectConfigsPath: java.io.File) {
  def apply(
      project: ProjectVersion,
      repoUrl: String,
      tagOrRevision: Option[String]
  ): Option[ProjectBuildConfig] = {
    val name = project.showName
    val projectDir = checkout(repoUrl, name, tagOrRevision)

    readProjectConfig(projectDir, repoUrl)
      .orElse(internalProjectConfigs(project.showName))
      .map { c =>
        if c.java.version.nonEmpty then c
        else c.copy(java = c.java.copy(version = discoverJavaVersion(projectDir)))
      }
      .map { c =>
        val default = ProjectBuildConfig.defaultMemoryRequest
        if c.memoryRequestMb > default then c
        else
          val discovered = discoverMemoryRequest(projectDir).filter(_ > default).getOrElse(default)
          c.copy(memoryRequestMb = discovered)
      }
      .filter(_ != ProjectBuildConfig.empty)
      .map { config =>
        println(s"Using custom project config for $name: ${toJson(config)}")
        config
      }
  }

  private def checkout(
      repoUrl: String,
      projectName: String,
      tagOrRevision: Option[String]
  ): os.Path = {
    val projectDir = os.temp.dir(prefix = s"repo-$projectName")
    os.proc(
      "git",
      "clone",
      repoUrl,
      projectDir,
      "--quiet",
      tagOrRevision.map("--branch=" + _).toList,
      "--depth=1"
    ).call(stderr = os.Pipe)
    projectDir
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

  private lazy val referenceConfig =
    ConfigSource
      .resources("buildPlan.reference.conf")
      .cursor()
      .flatMap(_.asConfigValue)
      .toOption

    // Resolve project config from mainted, internal project configs list
  private def internalProjectConfigs(projectName: String) = {
    val fallbackConfig = referenceConfig.foldLeft(ConfigFactory.empty)(_.withValue(projectName, _))
    val config = ConfigSource
      .file(internalProjectConfigsPath)
      .withFallback(ConfigSource.fromConfig(fallbackConfig))
      .at(projectName)
      .load[ProjectBuildConfig]

    config.left.foreach {
      case ConfigReaderFailures(
            CannotReadFile(file, Some(_: FileNotFoundException))
          ) =>
        System.err.println("Internal conifg projects not configured")
      case failure =>
        System.err.println(
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
            case CannotReadFile(_, Some(_: java.io.FileNotFoundException)) => true
            case _                                                         => false
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
    val JavaVersion = raw"java-version:\s*(.*)".r
    val JavaVersionNumber = raw"$OptQuote(\d+)$OptQuote".r
    val JavaVersionDistroVer = raw"$OptQuote(\w+)@(.*)$OptQuote".r
    val MatrixEntry = raw"(\w+):\s*\[(.*)\]".r
    // We can only supported this versions
    val allowedVersions = Seq(8, 11, 17)

    githubWorkflows(projectDir)
      .flatMap { path =>
        os.read
          .lines(path)
          .map(_.trim)
          .flatMap {
            case MatrixEntry(key, valuesList) if key.toLowerCase.contains("java") =>
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
      .minOption
      .map(_.toString)

  // Selects recommened amount of memory for project pod, based on the config files
  private def discoverMemoryRequest(projectDir: os.Path): Option[Int] =
    def readXmx(file: os.Path): Seq[Xmx.MegaBytes] =
      os.read.lines(file).collect { case Xmx(mBytes) => mBytes }

    val fromWorkflows = githubWorkflows(projectDir)
      .flatMap(readXmx)

    val fromOptsFiles = os
      .list(projectDir)
      .filter(
        _.lastOpt.exists { name =>
          name.contains("sbtopts") || name.contains("jvmopts") || name.contains("jvm-opts")
        }
      )
      .flatMap(readXmx)

    val fromBuild =
      val toCheck = projectDir / "build.sc" ::
        projectDir / "build.sbt" ::
        List(projectDir / "project").filter(os.exists(_)).flatMap(os.walk(_)).toList
      toCheck
        .filter(f => os.exists(f) && os.isFile(f))
        .flatMap(readXmx)

    val allCandidates = fromOptsFiles ++ fromWorkflows ++ fromBuild
    allCandidates.maxOption
  end discoverMemoryRequest

  private object Xmx {
    type MegaBytes = Int
    final val XmxPattern = raw".*-Xmx(\d+)(\w)?.*".r

    def unapply(text: String): Option[MegaBytes] =
      def commentStartIdx = text.indexOf("#") max text.indexOf("//")
      def isNotCommented = commentStartIdx < 0 || text.indexOf("-Xmx") < commentStartIdx
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
