package dashboard.core

import io.circe.{Codec, Decoder, Encoder}

/** A project in the community build, identified by org/repo format */
opaque type ProjectName <: String = String

object ProjectName:
  def apply(raw: String): Either[String, ProjectName] = raw match
    case s"${org}/${repo}" if org.nonEmpty && repo.nonEmpty => Right(raw)
    case s"${org}_${repo}" if org.nonEmpty && repo.nonEmpty => Right(s"$org/$repo")
    case _ => Left(s"Invalid project name format: $raw (expected org/repo)")

  def unsafeApply(raw: String): ProjectName =
    apply(raw).fold(msg => throw IllegalArgumentException(msg), identity)

  /** For Elasticsearch queries that may use legacy format */
  extension (name: ProjectName)
    def searchName: String = name
    def legacySearchName: String = name.replace("/", "_")
    def org: String = name.takeWhile(_ != '/')
    def repo: String = name.dropWhile(_ != '/').drop(1)

  given Codec[ProjectName] = Codec.from(
    Decoder.decodeString.emap(apply),
    Encoder.encodeString.contramap(identity)
  )

/** Semantic version with proper ordering */
opaque type SemVersion <: String = String

object SemVersion:
  def apply(version: String): Option[SemVersion] = version match
    case s"$_.$_.$_"    => Some(version)
    case s"$_.$_.$_-$_" => Some(version)
    case _              => None

  def unsafeApply(version: String): SemVersion =
    apply(version).getOrElse(throw IllegalArgumentException(s"Invalid version: $version"))

  given Ordering[SemVersion] with
    private object StableVersion:
      def unapply(v: String): Option[String] =
        if !v.contains('-') && v.split('.').length == 3 then Some(v) else None

    override def compare(x: String, y: String): Int = (x, y) match
      case (StableVersion(stable), other) if other.startsWith(stable) => 1
      case (other, StableVersion(stable)) if other.startsWith(stable) => -1
      case _                                                          => Ordering.String.compare(x, y)

  given Codec[SemVersion] = Codec.from(
    Decoder.decodeString.map(identity),
    Encoder.encodeString.contramap(identity)
  )

  extension (v: SemVersion)
    def isNewerThan(other: SemVersion): Boolean =
      val List(ref, ver) = List(other: String, v: String)
        .map(_.split("[.-]").flatMap(_.toIntOption).toList)
      if (other: String).startsWith(v) && ref.length > ver.length then false
      else if (v: String).startsWith(other) && ver.length > ref.length then true
      else
        val maxLength = ref.length max ver.length
        def loop(r: List[Int], ver: List[Int]): Boolean = (r, ver) match
          case (rHead :: rTail, vHead :: vTail) =>
            if vHead > rHead then true
            else if vHead < rHead then false
            else loop(rTail, vTail)
          case _ => false
        loop(ref.padTo(maxLength, 0), ver.padTo(maxLength, 0))

    def isNewerOrEqualTo(other: SemVersion): Boolean =
      v == other || v.isNewerThan(other)

/** Build status */
enum BuildStatus derives CanEqual:
  case Success, Failure, Started

object BuildStatus:
  def fromString(s: String): BuildStatus = s.toLowerCase match
    case "success" => Success
    case "failure" => Failure
    case "started" => Started
    case _         => Failure

  given Codec[BuildStatus] = Codec.from(
    Decoder.decodeString.map(fromString),
    Encoder.encodeString.contramap(_.toString.toLowerCase)
  )

/** Build tool used */
enum BuildTool derives CanEqual:
  case Sbt, Mill, ScalaCli, Unknown

object BuildTool:
  def fromString(s: String): BuildTool = s.toLowerCase match
    case "sbt"       => Sbt
    case "mill"      => Mill
    case "scala-cli" => ScalaCli
    case _           => Unknown

  given Codec[BuildTool] = Codec.from(
    Decoder.decodeString.map(fromString),
    Encoder.encodeString.contramap(_.toString.toLowerCase)
  )

/** Failure reason category */
enum FailureReason derives CanEqual:
  case Compilation, TestCompilation, Tests, Publish, Scaladoc, Build, Other

object FailureReason:
  given Codec[FailureReason] = Codec.from(
    Decoder.decodeString.map(s => FailureReason.valueOf(s)),
    Encoder.encodeString.contramap(_.toString)
  )

/** Scala version series for filtering history */
enum ScalaSeries derives CanEqual:
  case Lts33 // LTS 3.3.x
  case Lts39 // LTS 3.9.x (upcoming)
  case Next // Everything else (3.4, 3.5, 3.6, 3.7, 3.8, etc.)

object ScalaSeries:
  /** Determine which series a Scala version belongs to */
  def fromScalaVersion(version: String): ScalaSeries =
    if version.startsWith("3.3.") then Lts33
    else if version.startsWith("3.9.") then Lts39
    else Next

  def label(series: ScalaSeries): String = series match
    case Lts33 => "LTS 3.3"
    case Lts39 => "LTS 3.9"
    case Next  => "Next"

  def description(series: ScalaSeries): String = series match
    case Lts33 => "Long Term Support 3.3.x"
    case Lts39 => "Long Term Support 3.9.x (upcoming)"
    case Next  => "Latest development (3.4+, excluding LTS)"

  given Codec[ScalaSeries] = Codec.from(
    Decoder.decodeString.map(s => ScalaSeries.valueOf(s)),
    Encoder.encodeString.contramap(_.toString)
  )

/** Version type classification for filtering snapshots */
enum VersionType derives CanEqual:
  case Stable // 3.8.0
  case RC // 3.8.0-RC5
  case Nightly // 3.8.1-RC1-bin-20251228-e73ff2c-NIGHTLY
  case Snapshot // 3.8.0-RC4-bin-20251230-fab225a (not NIGHTLY)

object VersionType:
  /** Classify a Scala version string */
  def fromScalaVersion(version: String): VersionType =
    if version.endsWith("-NIGHTLY") then Nightly
    else if version.contains("-bin-") || version.contains("-SNAPSHOT") then Snapshot
    else if version.contains("-RC") then RC
    else Stable

  /** Check if a version should be shown based on filter settings */
  def shouldShow(version: String, excludeSnapshots: Boolean, excludeNightlies: Boolean): Boolean =
    val versionType = fromScalaVersion(version)
    val passesSnapshot = !excludeSnapshots || versionType != Snapshot
    val passesNightly = !excludeNightlies || versionType != Nightly
    passesSnapshot && passesNightly
