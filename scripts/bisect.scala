// Based on https://github.com/lampepfl/dotty/blob/main/project/scripts/bisect.scala
//> using lib "com.github.scopt::scopt:4.1.0"
//> using scala 3.3

import sys.process._
import scala.io.Source
import java.io.File
import java.nio.file.attribute.PosixFilePermissions
import java.nio.charset.StandardCharsets
import java.nio.file._

val communityBuildVersion = "v0.2.4"

@main def run(args: String*): Unit =
  val config = scopt.OParser
    .parse(Config.parser, args, Config())
    .getOrElse(sys.error("Failed to parse config"))
    
  val validationScript = config.validationScript
  val releases = Releases.fromRange(config.releasesRange)
  val releaseBisect = ReleaseBisect(validationScript, shouldFail = config.shouldFail, releases)

  releaseBisect.verifyEdgeReleases()

  if (!config.dryRun) then
    val (lastGoodRelease, firstBadRelease) = releaseBisect.bisectedGoodAndBadReleases()
    println(s"Last good release: ${lastGoodRelease.version}")
    println(s"First bad release: ${firstBadRelease.version}")
    println("\nFinished bisecting releases\n")

    val commitBisect = CommitBisect(validationScript, shouldFail = config.shouldFail, lastGoodRelease.hash, firstBadRelease.hash)
    commitBisect.bisect()

case class ValidationCommand(projectName: String = "", targets: String = "", extraScalacOptions: String = "", disabledScalacOption: String = "")
case class Config(
  dryRun: Boolean = false,
  releasesRange: ReleasesRange = ReleasesRange.all, 
  shouldFail: Boolean = false,
  openCommunityBuildDir: Path = Path.of(""),
  compilerDir: Path = Path.of(""),
  command: ValidationCommand = ValidationCommand()
){
  inline def withCommand(mapping: ValidationCommand => ValidationCommand) = copy(command = mapping(command))

  lazy val remoteValidationScript: File = ValidationScript.buildProject(
    projectName = command.projectName,
    targets = Option(command.targets).filter(_.nonEmpty),
    extraScalacOptions = command.extraScalacOptions,
    disabledScalacOption= command.disabledScalacOption,
    runId = s"bisect-${command.projectName}",
    buildURL= "",
    executeTests = false,
    openCBDir = openCommunityBuildDir
  )
  lazy val validationScript: File = 
    require(Files.exists(openCommunityBuildDir), "Open CB dir does not exist")
    require(Files.exists(compilerDir), "Compiler dir does not exist")
    ValidationScript.dockerRunBuildProject(command.projectName, remoteValidationScript, openCommunityBuildDir.toFile())
}

object Config{
  val parser = {
    import scopt.OParser
    val builder = OParser.builder[Config]
    import builder.*
    OParser.sequence(
      head("Scala 3 Open Community Build bisect", communityBuildVersion),
      opt[Unit]("dry-run")
        .action:
          (_, c) => c.copy(dryRun = true)
        .text("Don't try to bisect - just make sure the validation command works correctly"),
      opt[String]("releases")
        .action:
          (v, c) => c.copy(releasesRange = ReleasesRange.tryParse(v).getOrElse(c.releasesRange))
        .text("Bisect only releases from the given range 'first..last' (defaults to all releases)"),
      opt[Unit]("should-fail")
        .action:
          (_, c) => c.copy(shouldFail = true)
        .text("Expect the validation command to fail rather that succeed. This can be used e.g. to find out when some illegal code started to compile"),
      opt[String]("project-name")
        .action:
          (v, c) => c.withCommand(_.copy(projectName =v ))
        .text("Name of the project to run using GitHub coordinates")
        .required(),
      opt[String]("targets")
        .action:
          (v, c) => c.withCommand(_.copy(targets = v))
        .text("Comma delimited list of targets to limit scope of project building"),
      opt[String]("extra-scalac-options")
        .action:
          (v, c) => c.withCommand(_.copy(extraScalacOptions = v))
        .text("Extra scalac options passed to project build"),
      opt[String]("disabled-scalac-options")
        .action:
          (v, c) => c.withCommand(_.copy(disabledScalacOption = v))
        .text("Filtered out scalac options passed to project build"),
      opt[String]("community-build-dir")
        .action:
          (v, c) => c.copy(openCommunityBuildDir = Path.of(v))
        .text("Directory with community build project from which the project config would be resolved")
        .required(),
      opt[String]("compiler-dir")
        .action:
          (v, c) => c.copy(compilerDir = Path.of(v))
        .text("Directory containing Scala compiler repository, required for commit-based bissect")
        .required()
      ,
      checkConfig { c =>
        if !Files.exists(c.compilerDir) then failure("Compiler directory does not exist")
        else if !Files.exists(c.openCommunityBuildDir) then failure("Open Community Build directory does not exist")
        else success
      }
    )
  }
}

object ValidationScript:
  def buildProject(projectName: String, targets: Option[String], extraScalacOptions: String, disabledScalacOption: String, runId: String, buildURL: String, executeTests: Boolean, openCBDir: Path): File = tmpScript(openCBDir){
    val configPatch =
      if executeTests
      then ""
      else """* { "tests": "compile-only"} """
    raw"""
    |#!/usr/bin/env bash
    |set -e
    |
    |scalaVersion="$$1"       # e.g. 3.3.3
    |
    |DefaultConfig='{"memoryRequestMb":4096}'
    |ConfigFile="/opencb/.github/workflows/buildConfig.json"
    |if [[ ! -f "$$ConfigFile" ]]; then
    |    echo "Not found build config: $$ConfigFile"
    |    exit 1
    |fi
    |config () {
    |  path=".\"${projectName}\"$$@"
    |  jq -c -r "$$path" $$ConfigFile
    |}
    |
    |touch build-logs.txt  build-summary.txt
    |# Assume failure unless overwritten by a successful build
    |echo 'failure' > build-status.txt
    |
    |/build/build-revision.sh \
    |  "$$(config .repoUrl)" \
    |  "$$(config .revision)" \
    |  "$${scalaVersion}" \
    |  "" \
    |  "${targets.getOrElse("$(config .targets)")}" \
    |  "https://scala3.westeurope.cloudapp.azure.com/maven2/bisect/" \
    |  '1.6.2' \
    |  "$$(config .config '$configPatch' // $${DefaultConfig} '$configPatch')" \
    |  "$extraScalacOptions" \
    |  "$disabledScalacOption"
    |
    |grep -q "success" build-status.txt;
    |exit $$?
    """.stripMargin
  }

  def dockerRunBuildProject(projectName: String, validationScript: File, openCBDir: File): File =
    val scriptsPath = "/scripts/"
    val validationScriptPath="/scripts/validationScript.sh"
    assert(Files.exists(validationScript.toPath()))
    tmpScript(openCBDir.toPath)(raw"""
      |#!/usr/bin/env bash
      |set -e
      |scalaVersion=$$1
      |ConfigFile="${openCBDir.toPath().resolve(".github/workflows/buildConfig.json").toAbsolutePath()}"
      |DefaultJDK=11
      |javaVersion=$$(jq -r ".\"${projectName}\".config.java.version // $${DefaultJDK}" $$ConfigFile)
      |docker run --rm \
      |  -v ${validationScript.getAbsolutePath()}:$validationScriptPath \
      |  -v ${openCBDir.getAbsolutePath()}:/opencb/ \
      |  virtuslab/scala-community-build-project-builder:jdk$${javaVersion}-$communityBuildVersion \
      |  /bin/bash -c "$validationScriptPath $$scalaVersion"
    """.stripMargin)
  
  private def tmpScript(openCBDir: Path)(content: String): File =
    val executableAttr = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x"))
    val tmpPath = Files.createTempFile(openCBDir, "scala-bisect-validator", ".sh", executableAttr)
    val tmpFile = tmpPath.toFile

    print(s"Bisecting with validation script: ${tmpPath.toAbsolutePath}\n")
    print("#####################################\n")
    print(s"${content}\n\n")
    print("#####################################\n\n")

    tmpFile.deleteOnExit()
    Files.write(tmpPath, content.getBytes(StandardCharsets.UTF_8))
    tmpFile


case class ReleasesRange(first: Option[String], last: Option[String]):
  def filter(releases: Seq[Release]) =
    def releaseIndex(version: String): Int =
      val index = releases.indexWhere(_.version == version)
      assert(index > 0, s"${version} matches no nightly compiler release")
      index

    val startIdx = first.map(releaseIndex(_)).getOrElse(0)
    val endIdx = last.map(releaseIndex(_) + 1).getOrElse(releases.length)
    val filtered = releases.slice(startIdx, endIdx).toVector
    assert(filtered.nonEmpty, "No matching releases")
    filtered

object ReleasesRange:
  def all = ReleasesRange(None, None)
  def tryParse(range: String): Option[ReleasesRange] = range match
    case s"${first}..${last}" => Some(ReleasesRange(
      Some(first).filter(_.nonEmpty),
      Some(last).filter(_.nonEmpty)
    ))
    case _ => None

class Releases(val releases: Vector[Release])

object Releases:
  lazy val allReleases: Vector[Release] =
    val re = raw"""(?<=title=")(.+-bin-\d{8}-\w{7}-NIGHTLY)(?=/")""".r
    val html = Source.fromURL("https://repo1.maven.org/maven2/org/scala-lang/scala3-compiler_3/")
    re.findAllIn(html.mkString).map(Release.apply).toVector

  def fromRange(range: ReleasesRange): Vector[Release] = range.filter(allReleases)

case class Release(version: String):
  private val re = raw".+-bin-(\d{8})-(\w{7})-NIGHTLY".r
  def date: String =
    version match
      case re(date, _) => date
      case _ => sys.error(s"Could not extract date from release name: $version")
  def hash: String =
    version match
      case re(_, hash) => hash
      case _ => sys.error(s"Could not extract hash from release name: $version")

  override def toString: String = version


class ReleaseBisect(validationScript: File, shouldFail: Boolean, allReleases: Vector[Release]):
  assert(allReleases.length > 1, "Need at least 2 releases to bisect")

  private val isGoodReleaseCache = collection.mutable.Map.empty[Release, Boolean]

  def verifyEdgeReleases(): Unit =
    println(s"Verifying the first release: ${allReleases.head.version}")
    assert(isGoodRelease(allReleases.head), s"The evaluation script unexpectedly failed for the first checked release")
    println(s"Verifying the last release: ${allReleases.last.version}")
    assert(!isGoodRelease(allReleases.last), s"The evaluation script unexpectedly succeeded for the last checked release")

  def bisectedGoodAndBadReleases(): (Release, Release) =
    val firstBadRelease = bisect(allReleases)
    assert(!isGoodRelease(firstBadRelease), s"Bisection error: the 'first bad release' ${firstBadRelease.version} is not a bad release")
    val lastGoodRelease = firstBadRelease.previous
    assert(isGoodRelease(lastGoodRelease), s"Bisection error: the 'last good release' ${lastGoodRelease.version} is not a good release")
    (lastGoodRelease, firstBadRelease)

  extension (release: Release) private def previous: Release =
    val idx = allReleases.indexOf(release)
    allReleases(idx - 1)

  private def bisect(releases: Vector[Release]): Release =
    if releases.length == 2 then
      if isGoodRelease(releases.head) then releases.last
      else releases.head
    else
      val mid = releases(releases.length / 2)
      if isGoodRelease(mid) then bisect(releases.drop(releases.length / 2))
      else bisect(releases.take(releases.length / 2 + 1))

  private def isGoodRelease(release: Release): Boolean =
    isGoodReleaseCache.getOrElseUpdate(release, {
      println(s"Testing ${release.version}")
      val result = Seq(validationScript.getAbsolutePath, release.version).!
      val isGood = if(shouldFail) result != 0 else result == 0 // invert the process status if failure was expected
      println(s"Test result: ${release.version} is a ${if isGood then "good" else "bad"} release\n")
      isGood
    })

class CommitBisect(validationScript: File, shouldFail: Boolean, lastGoodHash: String, fistBadHash: String):
  def bisect(): Unit =
    println(s"Starting bisecting commits $lastGoodHash..$fistBadHash\n")
    // Always bootstrapped
    val scala3CompilerProject = "scala3-compiler-bootstrapped"
    val scala3Project = "scala3-bootstrapped"
    val mavenRepo = "https://scala3.westeurope.cloudapp.azure.com/maven2/bisect/"
    val validationCommandStatusModifier = if shouldFail then "! " else "" // invert the process status if failure was expected
    val bisectRunScript = raw"""
      |export NIGHTLYBUILD=yes
      |scalaVersion=$$(sbt "print ${scala3CompilerProject}/version" | tail -n1)
      |rm -r out
      |sbt "clean; set every sonatypePublishToBundle := Some(\"CommunityBuildRepo\" at \"$mavenRepo\"); ${scala3Project}/publish"
      |${validationCommandStatusModifier}${validationScript.getAbsolutePath} "$$scalaVersion"
    """.stripMargin
    "git bisect start".!
    s"git bisect bad $fistBadHash".!
    s"git bisect good $lastGoodHash".!
    Seq("git", "bisect", "run", "sh", "-c", bisectRunScript).!
    s"git bisect reset".!
