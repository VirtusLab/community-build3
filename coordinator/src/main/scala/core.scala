import org.jsoup._
import scala.jdk.CollectionConverters._
import java.nio.file._
import pureconfig._
import pureconfig.generic.derivation.default._
import pureconfig.generic.derivation.EnumConfigReader
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import org.json4s.FieldSerializer

type AsyncResponse[T] = ExecutionContext ?=> Future[T]

sealed case class Project(org: String, name: String):
  lazy val show = s"${org}_$name"
  def coordinates = s"$org/$name"
  def serialize: String = s"$org;$name"

  def raw = this match
    case _: StarredProject => Project(org, name)
    case _                 => this

class StarredProject(org: String, name: String)(val stars: Int) extends Project(org, name) {
  override def serialize = s"$org;$name;$stars"
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

case class MvnMapping(name: String, version: String, mvn: String, deps: Seq[String]):
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
    config: Option[ProjectBuildConfig]
)

enum TestingMode derives EnumConfigReader:
  case Disabled, CompileOnly, Full

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
    tests: TestingMode = TestingMode.Full,
    // We should use Option[Int] here, but json4s fails to resolve signature https://github.com/json4s/json4s/issues/1035
    memoryRequestMb: Int = 2048,
    sourcePatches: List[SourcePatch] = Nil
) derives ConfigReader

object ProjectBuildConfig {
  val empty = ProjectBuildConfig()
  final lazy val defaultMemoryRequest = empty.memoryRequestMb
}

case class SemVersion(major: Int, minor: Int, patch: Int, milestone: Option[String])
given Conversion[String, SemVersion] = version =>
  // There are multiple projects that don't follow standarnd naming convention, especially in snpashots
  // becouse of that it needs to be more flexible, e.g to handle: x.y-z-<hash>, x.y, x.y-milestone
  val parts = version.split('.').flatMap(_.split('-')).filter(_.nonEmpty)
  val versionNums = parts.take(3).takeWhile(_.forall(_.isDigit))
  def versionPart(idx: Int) = versionNums.lift(idx).flatMap(_.toIntOption).getOrElse(0)
  val milestone = Some(parts.drop(versionNums.size))
    .filter(_.nonEmpty)
    .map(_.mkString("-"))
  SemVersion(
    major = versionPart(0),
    minor = versionPart(1),
    patch = versionPart(2),
    milestone = milestone
  )
