package scala.versions

import java.nio.file.Path
import java.nio.file.Files
import java.time.LocalDate
import scala.util.chaining.*

import SemVersion.given

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

  private case class VersionComponents(
      major: Int,
      minor: Int,
      patch: Int,
      rc: Int,
      date: Long,
      tail: String
  )

  private given Ordering[VersionComponents] = Ordering.by:
    case VersionComponents(major, minor, patch, rc, date, tail) =>
      (major, minor, patch, rc, date, tail)

  private val VersionPrefix = """^(\d+)\.(\d+)\.(\d+)(?:-(.*))?$""".r

  private def isDate(value: String): Boolean =
    value.length == 8 && value.forall(_.isDigit)

  private def parseBinParts(afterBin: List[String]): (Long, String) =
    afterBin match
      case dateStr :: rest if isDate(dateStr) => (dateStr.toLong, rest.mkString("-"))
      case _                                  => (0L, afterBin.mkString("-"))

  private def parseComponents(version: String): VersionComponents =
    version match
      case VersionPrefix(maj, min, pat, null) =>
        VersionComponents(maj.toInt, min.toInt, pat.toInt, Int.MaxValue, 0L, "")
      case VersionPrefix(maj, min, pat, suffix) =>
        val major = maj.toInt
        val minor = min.toInt
        val patch = pat.toInt
        val parts = suffix.split('-').toList

        def withRc(rc: Int, rest: List[String]): VersionComponents =
          rest match
            case Nil =>
              VersionComponents(major, minor, patch, rc, 0L, "")
            case "bin" :: afterBin =>
              val (date, tail) = parseBinParts(afterBin)
              VersionComponents(major, minor, patch, rc, date, tail)
            case other =>
              VersionComponents(major, minor, patch, rc, 0L, other.mkString("-"))

        parts match
          case s"RC$rcNum" :: rest if rcNum.forall(_.isDigit) =>
            withRc(rcNum.toInt, rest)
          case "bin" :: afterBin =>
            val (date, tail) = parseBinParts(afterBin)
            VersionComponents(major, minor, patch, 0, date, tail)
          case _ =>
            VersionComponents(major, minor, patch, 0, 0L, suffix)

  private def compareVersions(a: String, b: String): Int =
    Ordering[VersionComponents].compare(parseComponents(a), parseComponents(b))

  given Ordering[SemVersion] with
    object StableVersion:
      def unapply(v: String): Option[String] =
        if !v.contains('-') && v.split('.').size == 3 then Some(v) else None

    override def compare(x: String, y: String): Int = (x, y) match
      case (StableVersion(stable), other) if other.startsWith(stable) => 1
      case (other, StableVersion(stable)) if other.startsWith(stable) => -1
      case _                                                          => compareVersions(x, y)
