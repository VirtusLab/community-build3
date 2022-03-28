import org.jsoup._
import collection.JavaConverters._
import java.nio.file._
import pureconfig._
import pureconfig.generic.derivation.default._
import pureconfig.generic.derivation.EnumConfigReader

case class Project(org: String, name: String)(val stars: Int): // stars may change...
  def show = s"$org%$name%$stars"

object Project:
  def load(line: String) =
    val splitted = line.split("%")
    Project(splitted(0), splitted(1))(splitted(2).toInt)

case class ProjectVersion(p: Project, v: String)

case class MvnMapping(name: String, version: String, mvn: String, deps: Seq[String]):
  def show = (Seq(name, version, mvn) ++ deps).mkString(",")

object MvnMapping:
  def load(s: String) =
    val Array(name, version, mvn, deps*) = s.split(",")
    MvnMapping(name, version, mvn, deps)

case class TargetId(org: String, name: String):
  def asMvnStr = org + "%" + name

case class Dep(id: TargetId, version: String):
  def asMvnStr = id.asMvnStr + "%" + version

case class Target(id: TargetId, deps: Seq[Dep])

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
    name: String,
    dependencies: Array[String],
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
case class ProjectBuildConfig(
    projects: ProjectsConfig = ProjectsConfig(),
    java: JavaConfig = JavaConfig(),
    sbt: SbtConfig = SbtConfig(),
    mill: MillConfig = MillConfig(),
    tests: TestingMode = TestingMode.Full
) derives ConfigReader

object ProjectBuildConfig {
  val empty = ProjectBuildConfig()
}

case class SemVersion(major: Int, minor: Int, patch: Int, milestone: Option[String])
given Conversion[String, SemVersion] = _ match {
  // format: off
  case s"$major.$minor.$patch-$milestone" => SemVersion(major.toInt, minor.toInt, patch.toInt, Some(milestone))
  case s"$major.$minor.$patch"            => SemVersion(major.toInt, minor.toInt, patch.toInt, None)
  case s"$major.$minor"                   => SemVersion(major.toInt, minor.toInt, 0, None)
  case s"$major"                          => SemVersion(major.toInt, 0, 0, None)
  // format: on
}

given Ordering[SemVersion] = (x: SemVersion, y: SemVersion) => {
  def compareMilestones = (x.milestone, y.milestone) match {
    case (None, None)    => 0
    case (Some(_), None) => -1 // x.y.z-RC1 < x.y.z
    case (None, Some(_)) => 1 // x.y.z > x.y.z-RC1
    case (Some(x), Some(y)) =>
      val commonPrefix = x.intersect(y)
      val left = x.stripPrefix(commonPrefix)
      val right = y.stripPrefix(commonPrefix)
      if left.isEmpty && right.isEmpty then 0
      else if left.isEmpty then -1 // RC < RC2
      else if right.isEmpty then 1 // RC2 > RC
      else if left.forall(_.isDigit) && right.forall(_.isDigit) then left.toInt compare right.toInt
      else left compare right
  }

  val ordering =
    if x.major != y.major then x.major compare y.major
    else if x.minor != y.minor then x.minor compare y.minor
    else if x.patch != y.patch then x.patch compare y.patch
    else compareMilestones
  -ordering // We want to have results ordered starting with the latests version
}
