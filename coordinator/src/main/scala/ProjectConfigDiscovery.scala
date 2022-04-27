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
    val githubDir = projectDir / ".github"
    if !os.exists(githubDir) then None
    else
      val OptQuote = "['\"]?".r
      val JavaVersion = raw"java-version:\s*(.*)".r
      val JavaVersionNumber = raw"$OptQuote(\d+)$OptQuote".r
      val JavaVersionDistroVer = raw"$OptQuote(\w+)@(.*)$OptQuote".r
      val MatrixEntry = raw"(\w+):\s*\[(.*)\]".r
      // We can only supported this versions
      val allowedVersions = Seq(8, 11, 17)
      os.walk
        .stream(githubDir)
        .filter(os.isFile)
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

}
