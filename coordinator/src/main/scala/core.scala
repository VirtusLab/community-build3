import org.jsoup._
import collection.JavaConverters._
import java.nio.file._

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
    val d = s.split(",")
    MvnMapping(d(0), d(1),d(2),d.drop(3))

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

// Make it a class to allow for serialization using gson
class ProjectConfig(javaVersion: Option[String], javaOptions: Array[String]){
  override def toString(): String = s"ProjectConfig(javaVersion=$javaVersion)"
}
object ProjectConfig{
  val empty = ProjectConfig(javaVersion = None, javaOptions = Nil)
}

