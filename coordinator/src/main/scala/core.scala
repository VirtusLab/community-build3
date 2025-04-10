import pureconfig._
import pureconfig.generic.derivation.EnumConfigReader
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

type AsyncResponse[T] = ExecutionContext ?=> Future[T]

sealed case class Project(organization: String, repository: String):
  lazy val show = s"${organization}_$repository"
  def coordinates = s"$organization/$repository"
  def serialize: String = s"$organization;$repository"

  def raw = this match
    case _: StarredProject => Project(organization, repository)
    case _                 => this
class StarredProject(organization: String, repository: String)(val stars: Int) extends Project(organization, repository) {
  override def serialize = s"$organization;$repository;$stars"
}

object Project:
  given Ordering[Project] = Ordering.by(_.show)

  def load(line: String) =
    line match {
      case s"$org;$repo;$stars" => StarredProject(org, repo)(stars.toInt)
      case s"$org;$repo"        => Project(org, repo)
      case s"$org/$repo"        => Project(org, repo)
      case s"$org,$repo"        => Project(org, repo)
    }

case class ProjectVersion(p: Project, v: String) {
  def showName = p.show
}

case class MvnMapping(
    name: String,
    version: String,
    mvn: String,
    deps: Seq[String]
):
  def show = (Seq(name, version, mvn) ++ deps).mkString(",")

object MvnMapping:
  def load(s: String) =
    val Array(name, version, mvn, deps*) = s.split(","): @unchecked
    MvnMapping(name, version, mvn, deps)

case class TargetId(org: String, name: String):
  def asMvnStr = org + "%" + name

case class Dep(id: TargetId, version: String):
  def asMvnStr = id.asMvnStr + "%" + version

case class Target(id: TargetId, deps: Seq[Dep])
object Target:
  object BuildAll extends Target(TargetId("*", "*"), Nil)

case class LoadedProject(p: Project, v: String, targets: Seq[Target])

case class DependencyGraph(scalaRelease: String, projects: Seq[LoadedProject])

case class BuildStep(
    p: Project,
    originalVersion: String,
    publishVersion: Option[String],
    targets: Seq[TargetId],
    depOverrides: Seq[Dep]
)

case class BuildPlan(scalaVersion: String, steps: Seq[Seq[BuildStep]])

case class ProjectBuildDef(
    project: Project,
    dependencies: Array[Project],
    repoUrl: String,
    revision: String,
    version: String,
    targets: String,
    publishedScalaVersion: Option[String],
    config: Option[ProjectBuildConfig]
)

enum TestingMode derives EnumConfigReader:
  case Disabled, CompileOnly, Full
object TestingMode:
  def default = TestingMode.Full

// Community projects configs
case class JavaConfig(version: Option[String] = None) derives ConfigReader
case class SbtConfig(commands: List[String] = Nil, options: List[String] = Nil) derives ConfigReader
case class MillConfig(options: List[String] = Nil) derives ConfigReader
case class ProjectOverrides(tests: Option[TestingMode] = None) derives ConfigReader
case class ProjectsConfig(
    exclude: List[String] = Nil,
    overrides: Map[String, ProjectOverrides] = Map.empty
) derives ConfigReader
// Describes a simple textual replecement performed on path (relative Unix-style path to the file
case class SourcePatch(path: String, pattern: String, replaceWith: String) derives ConfigReader
case class ProjectBuildConfig(
    projects: ProjectsConfig = ProjectsConfig(),
    java: JavaConfig = JavaConfig(),
    sbt: SbtConfig = SbtConfig(),
    mill: MillConfig = MillConfig(),
    tests: TestingMode = TestingMode.default,
    sourceVersion: Option[String] = None,
    migrationVersions: List[String] = Nil,   
    sourcePatches: List[SourcePatch] = Nil
) derives ConfigReader

object ProjectBuildConfig {
  val empty = ProjectBuildConfig()
}

case class SemVersion(
    major: Int,
    minor: Int,
    patch: Int,
    milestone: Option[String] = None
) extends Ordered[SemVersion] {
  def show = milestone.foldLeft(s"$major.$minor.$patch")(_ + "-" + _)
  override def compare(that: SemVersion): Int = {
    that match
      case SemVersion(`major`, `minor`, `patch`, milestone) =>
        def parseMilestone(v: Option[String]): Int = v.fold(0){
          _.filter(_.isDigit).toIntOption.getOrElse(Int.MaxValue)
        }
        parseMilestone(this.milestone).compareTo(parseMilestone(milestone))
      case SemVersion(`major`, `minor`, patch, _) =>  this.patch.compareTo(patch)
      case SemVersion(`major`, minor, _, _) =>  this.minor.compareTo(minor)
      case SemVersion(major, _, _, _) =>  this.major.compareTo(major)
  }
}
object SemVersion {
  def unsafe(version: String): SemVersion = SemVersion.unapply(version).get
  def unapply(version: String): Option[SemVersion] = util.Try {
    // There are multiple projects that don't follow standarnd naming convention, especially in snpashots
    // becouse of that it needs to be more flexible, e.g to handle: x.y-z-<hash>, x.y, x.y-milestone
    val parts = version.split('.').flatMap(_.split('-')).filter(_.nonEmpty)
    val versionNums = parts.take(3).takeWhile(_.forall(_.isDigit))
    def versionPart(idx: Int) =
      versionNums.lift(idx).flatMap(_.toIntOption).getOrElse(0)
    val milestone = Some(parts.drop(versionNums.size))
      .filter(_.nonEmpty)
      .map(_.mkString("-"))
    SemVersion(
      major = versionPart(0),
      minor = versionPart(1),
      patch = versionPart(2),
      milestone = milestone
    )
  }.toOption
}
