import org.jsoup._
import collection.JavaConverters._
import java.nio.file._
import pureconfig._
import pureconfig.generic.derivation.default._

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

case class ProjectBuildDef(name: String, dependencies: Array[String], repoUrl: String, revision: String, version: String, targets: String, config: Option[ProjectConfig])

// Community projects configs
case class JavaConfig(version: Option[String] = None) derives ConfigReader
case class SbtConfig(exclude: List[String] = Nil, commands: List[String] = Nil, options: List[String] = Nil) derives ConfigReader
case class ProjectConfig(java: JavaConfig, sbt: SbtConfig) derives ConfigReader
object ProjectConfig{
  val empty = ProjectConfig(java = JavaConfig(), sbt = SbtConfig())
}
