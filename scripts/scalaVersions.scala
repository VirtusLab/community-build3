package scala.versions

import java.nio.file.Path
import java.nio.file.Files
import java.time.LocalDate
import scala.util.chaining.*

lazy val Stable: List[SemVersion] =
  getVersions(raw"<version>(\d+\.\d+\.\d+(-RC\d+)?)</version>".r)

lazy val Nightly: List[SemVersion] =
  getVersions(raw"<version>(.+-bin-\d{8}-\w{7}-NIGHTLY)</version>".r)

lazy val Releases: List[SemVersion] = 
  (Stable ++ Nightly).sorted

def getVersions(versionPattern: scala.util.matching.Regex): List[SemVersion] = {
  val xml = io.Source.fromURL(
    "https://repo.scala-lang.org/artifactory/maven-nightlies/org/scala-lang/scala3-compiler_3/maven-metadata.xml"
  )
  versionPattern
    .findAllMatchIn(xml.mkString)
    .map(_.group(1))
    .filter(_ != null)
    .flatMap(SemVersion.validate)
    .toList
    .distinct
    .sorted
}

opaque type SemVersion <: String = String
object SemVersion:
  def validate(version: String): Option[SemVersion] = version match {
    case s"$major.$minor.$patch"         => Some(version)
    case s"$major.$minor.$patch-$suffix" => Some(version)
    case _                               => None
  }
given Ordering[SemVersion] with {
  object StableVersion {
    def unapply(v: String): Option[String] =
      if !v.contains('-') && v.split('.').size == 3 then Some(v) else None
  }
  override def compare(x: String, y: String): Int = (x, y) match {
    case (StableVersion(stable), other) if other.startsWith(stable) => 1
    case (other, StableVersion(stable)) if other.startsWith(stable) => -1
    case _                                                          => Ordering.String.compare(x, y)
  }
}
