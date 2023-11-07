import org.jsoup._
import scala.jdk.CollectionConverters._
import java.nio.file._
import scala.sys.process._
import scala.concurrent.Future

trait CacheDriver[K, T]:
  def write(v: T): String
  def load(data: String, key: K): T
  def dest(k: K): Path

def cachedSingle[V](dest: String)(op: => V)(using CacheDriver[String, V]): V =
  cached((_: String) => op)(dest)

def cached[V, K](op: K => V)(using cacheDriver: CacheDriver[K, V]): K => V =
  (k: K) =>
    val dest = cacheDriver.dest(k)
    if Files.exists(dest) then cacheDriver.load(String(Files.readAllBytes(dest)), k)
    else
      val res = op(k)
      Files.createDirectories(dest.getParent)
      Files.write(dest, cacheDriver.write(res).getBytes)
      res

def cachedAsync[V, K](op: K => AsyncResponse[V])(using
    cacheDriver: CacheDriver[K, V]
): K => AsyncResponse[V] =
  (k: K) =>
    val dest = cacheDriver.dest(k)
    if Files.exists(dest) then Future { cacheDriver.load(String(Files.readAllBytes(dest)), k) }
    else
      op(k).map { res =>
        Files.createDirectories(dest.getParent)
        Files.write(dest, cacheDriver.write(res).getBytes)
        res
      }

val dataPath = Paths.get("data")

given CacheDriver[Project, ProjectModules] with
  def write(v: ProjectModules): String =
    v.mvs.map(mv => (mv.version +: mv.modules).mkString(",")).mkString("\n")

  def load(data: String, key: Project): ProjectModules =
    val mvs = data.linesIterator.toSeq.map { l =>
      val v +: mds = l.split(",").toSeq: @unchecked
      ModuleInVersion(v, mds)
    }
    ProjectModules(key, mvs)

  def dest(v: Project): Path =
    dataPath.resolve("projectModules").resolve(v.org + "_" + v.name + ".csv")

given CacheDriver[String, Seq[StarredProject]] with
  def write(v: Seq[StarredProject]): String =
    v.map(_.serialize).mkString("\n")

  def load(data: String, k: String): Seq[StarredProject] =
    data.linesIterator
      .map(Project.load)
      .collect { case sp: StarredProject => sp }
      .toSeq

  def dest(v: String): Path = dataPath.resolve(v)

object DepOps:
  def write(d: Dep) = Seq(d.id.org, d.id.name, d.version).mkString("%")
  def load(l: String): Dep =
    val d = l.split('%')
    Dep(TargetId(d(0), d(1)), d(2))

given CacheDriver[ModuleVersion, Target] with
  def write(v: Target): String =
    (Dep(v.id, "_") +: v.deps).map(DepOps.write).mkString("\n"): @unchecked

  def load(data: String, key: ModuleVersion): Target =
    val Dep(id, _) +: deps =
      data.linesIterator.toSeq.map(DepOps.load): @unchecked
    Target(id, deps)

  def dest(v: ModuleVersion): Path =
    val fn = Seq(v.p.org, v.p.name, v.name, v.version).mkString("", "_", ".csv")
    dataPath.resolve("targets").resolve(fn)
