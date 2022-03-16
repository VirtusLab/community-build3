import sbt._
import sbt.Keys._
import sjsonnew._, LList.:*:
import sjsonnew.BasicJsonProtocol._
import sjsonnew.support.scalajson.unsafe.{Converter, Parser}

sealed abstract class BuildStepResult(val stringValue: String) {
  def jsonValue = s""""$stringValue""""
}
sealed abstract class FailedBuildStep(name: String, info: List[(String, String)])
    extends BuildStepResult(name) {
  override def jsonValue =
    if (info.isEmpty) s""""$name""""
    else {
      val infoFields = info.map { case (k, v) => s""""$k": "$v"""" }.mkString(", ")
      s""""$name": {$infoFields}"""
    }
}
case object Ok extends BuildStepResult("ok")
case object Skipped extends BuildStepResult("skipped")
case object Failed extends FailedBuildStep("failed", Nil)
case class WrongVersion(expected: String, got: String)
    extends FailedBuildStep("wrongVersion", List("expected" -> expected, "got" -> got))
case class BuildError(msg: String) extends FailedBuildStep("buildError", List("reason" -> msg))

case class ModuleTargetsResults(
    compile: BuildStepResult,
    doc: BuildStepResult,
    testsCompile: BuildStepResult,
    testsExecute: BuildStepResult,
    publish: BuildStepResult
) {
  def hasFailedStep: Boolean = this.productIterator.exists(_.isInstanceOf[FailedBuildStep])
  def jsonValues: List[String] = List(
    "compile" -> compile,
    "doc" -> doc,
    "test-compile" -> testsCompile,
    "test" -> testsExecute,
    "publish" -> publish
  ).map { case (key, value) =>
    s""""$key": "${value.stringValue}""""
  }
}
case class ModuleBuildResults(
    artifactName: String,
    results: ModuleTargetsResults
) {
  lazy val toJson = {
    val resultsJson = results.jsonValues.mkString(", ")
    s""""$artifactName": {$resultsJson}"""
  }
}

class ProjectBuildFailureException extends Exception

object WithExtractedScala3Suffix {
  def unapply(s: String): Option[(String, String)] = {
    val parts = s.split("_")
    if (parts.length > 1 && parts.last.startsWith("3")) {
      Some(parts.init.mkString("_"), parts.last)
    } else {
      None
    }
  }
}

object CommunityBuildPlugin extends AutoPlugin {
  override def trigger = allRequirements

  val runBuild = inputKey[Unit]("")
  val moduleMappings = inputKey[Unit]("")
  val publishResults = taskKey[Unit]("")
  val publishResultsConf = taskKey[PublishConfiguration]("")

  import complete.DefaultParsers._

  def mvnRepoPublishSettings = sys.env
    .get("CB_MVN_REPO_URL")
    .filter(_.nonEmpty)
    .map("Community Build Repo" at _)
    .map { ourResolver =>
      Seq(
        publishResultsConf :=
          publishM2Configuration.value
            .withPublishMavenStyle(true)
            .withResolverName(ourResolver.name)
            .withOverwrite(true),
        publishResults := Classpaths.publishTask(publishResultsConf).value,
        externalResolvers := ourResolver +: externalResolvers.value
      )
    }
    .getOrElse(Nil)

  override def projectSettings = Seq(
    scalacOptions := {
      // Flags need to be unique
      val options = CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((3, 0)) => Nil
        case _            =>
          // Ignore deprecations, replace them with info ()
          Seq("-Wconf:cat=deprecation:i")
      }
      options.foldLeft(scalacOptions.value) { case (options, flag) =>
        if (options.contains(flag)) options
        else options :+ flag
      }
    }
  ) ++ mvnRepoPublishSettings

  private def stripScala3Suffix(s: String) = s match {
    case WithExtractedScala3Suffix(prefix, _) => prefix; case _ => s
  }

  /** Helper command used to set correct version for publishing Defined due to a bug in sbt which
    * does not allow for usage of `set every` with scoped keys We need to use this task instead of
    * `set every Compile/version := ???`, becouse it would set given value also in Jmh/version or
    * Jcstress/version scopes, leading build failures
    */
  val setPublishVersion = Command.args("setPublishVersion", "<args>") { case (state, args) =>
    args.headOption
      .orElse(sys.props.get("communitybuild.version"))
      .filter(_.nonEmpty)
      .fold {
        System.err.println("No explicit version found in setPublishVersion command, skipping")
        state
      } { version =>
        println(s"Setting publish version to $version")

        val structure = sbt.Project.extract(state).structure
        def setVersionCmd(project: String) = s"""set $project/version := "$version" """

        structure.allProjectRefs
          .collect {
            // Filter out root project, we need to use ThisBuild instead of root project name
            case ref @ ProjectRef(uri, project) if structure.rootProject(uri) != project => ref
          }
          .foldLeft(Command.process(setVersionCmd("ThisBuild"), state)) { case (state, ref) =>
            // Check if project needs explicit overwrite, skip otherwise
            val s = sbt.Project.extract(state).structure
            val projectVersion = (ref / Keys.version).get(s.data).get
            if (projectVersion == version) state
            else
              try Command.process(setVersionCmd(ref.project), state)
              catch {
                case ex: Exception =>
                  System.err.println(s"Failed to set publish version in ${ref.project}, skipping")
                  state
              }
          }
      }
  }

  // Create mapping from org%artifact_name to project name
  val mkMappings = Def.task {
    val cState = state.value
    val s = sbt.Project.extract(cState).structure
    val refs = s.allProjectRefs
    refs.map { r =>
      val current: ModuleID = (r / projectID).get(s.data).get
      val sv = (r / scalaVersion).get(s.data).get
      val sbv = (r / scalaBinaryVersion).get(s.data).get
      val name = CrossVersion(current.crossVersion, sv, sbv)
        .fold(current.name)(_(current.name))
      val mappingKey = s"${current.organization}%${stripScala3Suffix(name)}"
      mappingKey -> r
    }
  }

  lazy val ourVersion =
    Option(sys.props("communitybuild.version")).filter(_.nonEmpty)

  override def globalSettings = Seq(
    moduleMappings := { // Store settings in file to capture its original scala versions
      val moduleIds = mkMappings.value
      IO.write(
        file("community-build-mappings.txt"),
        moduleIds.map(v => v._1 + " " + v._2.project).mkString("\n")
      )
    },
    runBuild := {
      val scalaVersionArg :: configJson :: ids = spaceDelimited("<arg>").parsed.toList
      val config = Parser
        .parseFromString(configJson)
        .flatMap(Converter.fromJson[ProjectBuildConfig](_)(ProjectBuildConfigFormat))
        .getOrElse(ProjectBuildConfig())

      val cState = state.value
      val extracted = sbt.Project.extract(cState)
      val s = extracted.structure
      val refs = s.allProjectRefs
      val refsByName = s.allProjectRefs.map(r => r.project -> r).toMap
      val scalaBinaryVersionUsed = CrossVersion.binaryScalaVersion(scalaVersionArg)
      val scalaBinaryVersionSuffix = "_" + scalaBinaryVersionUsed
      val scalaVersionSuffix = "_" + scalaVersionArg

      // Ignore projects for which crossScalaVersion does not contain any binary version
      // of currently used Scala version. This is important in case of usage projectMatrix and
      // Scala.js / Scala Native, which can define multiple projects for different major Scala versions
      // but with common target, eg. foo2_13, foo3
      def projectSupportsScalaBinaryVersion(
          projectRef: ProjectRef
      ): Boolean = {
        val hasCrossVersionSet = extracted
          .get(projectRef / crossScalaVersions)
          .exists {
            // Do not make exact check to handle projects using 3.0.0-RC* versions
            CrossVersion
              .binaryScalaVersion(_)
              .startsWith(scalaBinaryVersionUsed)
          }
        // Workaround for scalatest/circe which does not set crossScalaVersions correctly
        def matchesName =
          scalaBinaryVersionUsed.startsWith("3") && projectRef.project.contains("Dotty")
        hasCrossVersionSet || matchesName
      }

      def selectProject(projects: Seq[(String, ProjectRef)]): ProjectRef = {
        require(projects.nonEmpty, "selectProject with empty projects argument")
        val target = projects.head._1
        projects.map(_._2) match {
          case Seq(project) => project
          case projects =>
            projects
              .find(projectSupportsScalaBinaryVersion)
              .getOrElse {
                val selected = projects.head
                System.err.println(
                  s"None of projects in group ${projects.map(_.project)} uses current Scala binary version, using random: ${selected.project}"
                )
                selected
              }
        }
      }

      val originalModuleIds: Map[String, ProjectRef] = IO
        .readLines(file("community-build-mappings.txt"))
        .map(_.split(' '))
        .map(d => d(0) -> refsByName(d(1)))
        .groupBy(_._1)
        .mapValues(selectProject)
        .toMap
      val moduleIds: Map[String, ProjectRef] = mkMappings.value
        .groupBy(_._1)
        .mapValues(selectProject)
        .toMap

      def simplifiedModuleId(id: String) =
        // Drop first part of mapping (organization%)
        id.substring(id.indexOf('%') + 1)
      val simplifiedModuleIds = moduleIds.map { case (key, value) =>
        simplifiedModuleId(key) -> value
      }

      println("Starting build...")

      // Find projects that matches maven
      val topLevelProjects = (
        for {
          id <- ids
          testedSuffixes = Seq("", scalaVersionSuffix, scalaBinaryVersionSuffix) ++
            Option("Dotty").filter(_ => scalaBinaryVersionUsed.startsWith("3"))
          testedFullIds = testedSuffixes.map(id + _)
          candidates = for (fullId <- testedFullIds)
            yield Stream(
              refsByName.get(fullId),
              originalModuleIds.get(fullId),
              moduleIds.get(fullId),
              simplifiedModuleIds.get(simplifiedModuleId(fullId))
            ).flatten
        } yield candidates.flatten.headOption.getOrElse {
          println(s"""Module mapping missing:
            |  id: $id
            |  testedIds: $testedFullIds
            |  scalaVersionSuffix: $scalaVersionSuffix
            |  scalaBinaryVersionSuffix: $scalaBinaryVersionSuffix
            |  refsByName: ${refsByName.keySet}
            |  originalModuleIds: ${originalModuleIds.keySet}
            |  moduleIds: ${moduleIds.keySet}
            |""")
          throw new Exception("Module mapping missing")

        }
      ).toSet

      val projectDeps = s.allProjectPairs.map { case (rp, ref) =>
        ref -> rp.dependencies.map(_.project)
      }.toMap

      @annotation.tailrec
      def flatten(soFar: Set[ProjectRef], toCheck: Set[ProjectRef]): Set[ProjectRef] =
        toCheck match {
          case e if e.isEmpty => soFar
          case pDeps =>
            val deps = projectDeps(pDeps.head).filterNot(soFar.contains)
            flatten(soFar ++ deps, pDeps.tail ++ deps)
        }

      val allToBuild = flatten(topLevelProjects, topLevelProjects)
      println("Projects: " + allToBuild.map(_.project))

      class TaskEvaluator(val project: ProjectRef, initialState: State) {
        def eval(task: TaskKey[_]): BuildStepResult = {
          try {
            // we should reuse state here
            sbt.Project
              .runTask(project / task, initialState)
              .fold[BuildStepResult](BuildError("Cannot eval command")) { case (_, result) =>
                result.toEither match {
                  case Right(_) => Ok
                  case Left(_)  => Failed
                }
              }
          } catch {
            case ex: Exception =>
              BuildError(s"Evaluation error: ${ex.getMessage}")
          }
        }
        def evalAsDependencyOf(
            dependencies: BuildStepResult*
        )(task: TaskKey[_]): BuildStepResult = {
          val shouldSkip = dependencies.exists {
            case _: FailedBuildStep => true
            case Skipped            => true
            case _                  => false
          }
          if (shouldSkip) Skipped
          else eval(task)
        }
      }

      val projectsBuildResults = allToBuild.map { r =>
        val evaluator = new TaskEvaluator(r, cState)
        val s = sbt.Project.extract(cState).structure
        val projectName = (r / moduleName).get(s.data).get
        println(s"Starting build for $r ($projectName)...")

        val overrideSettings = config.projects.overrides
          .getOrElse(projectName, ProjectOverrides())
        val testingMode = overrideSettings.tests.getOrElse(config.tests)

        import evaluator._
        val results = {
          val compileResult = eval(Compile / compile)
          val docsResult = evalAsDependencyOf(compileResult)(Compile / doc)
          val testsCompileResult = testingMode match {
            case TestingMode.Disabled => Skipped
            case _                    => evalAsDependencyOf(compileResult)(Test / compile)
          }
          val testsExecuteResult = testingMode match {
            case TestingMode.Full => evalAsDependencyOf(testsCompileResult)(Test / test)
            case _                => Skipped
          }
          ModuleTargetsResults(
            compile = compileResult,
            doc = docsResult,
            testsCompile = testsCompileResult,
            testsExecute = testsExecuteResult,
            publish = ourVersion.fold[BuildStepResult](Skipped) { version =>
              val currentVersion = (r / Keys.version)
                .get(s.data)
                .getOrElse(sys.error(s"${r.project}/version not set"))
              if (currentVersion != version)
                WrongVersion(expected = version, got = currentVersion)
              else evalAsDependencyOf(compileResult, docsResult)(Compile / publishResults)
            }
          )
        }
        ModuleBuildResults(
          artifactName = projectName,
          results = results
        )
      }
      val buildSummary = projectsBuildResults
        .map(_.toJson)
        .mkString("{", ", ", "}")
      println(s"""
          |************************
          |Build summary:
          |${buildSummary}
          |************************""".stripMargin)
      IO.write(file("..") / "build-summary.txt", buildSummary)

      val hasFailedSteps = projectsBuildResults.exists(_.results.hasFailedStep)
      val buildStatus =
        if (hasFailedSteps) "failure"
        else "success"
      IO.write(file("..") / "build-status.txt", buildStatus)
      if (hasFailedSteps) throw new ProjectBuildFailureException
    },
    (runBuild / aggregate) := false
  )

  sealed trait TestingMode
  object TestingMode {
    case object Disabled extends TestingMode
    case object CompileOnly extends TestingMode
    case object Full extends TestingMode
  }

  // Community projects configs
  case class ProjectOverrides(tests: Option[TestingMode] = None)
  case class ProjectsConfig(
      exclude: List[String] = Nil,
      overrides: Map[String, ProjectOverrides] = Map.empty
  )
  case class ProjectBuildConfig(
      projects: ProjectsConfig = ProjectsConfig(),
      tests: TestingMode = TestingMode.Full
  )
  // Serialization
  implicit object TestingModeEnumJsonFormat extends JsonFormat[TestingMode] {
    def write[J](x: TestingMode, builder: Builder[J]): Unit = "full"
    def read[J](jsOpt: Option[J], unbuilder: Unbuilder[J]): TestingMode =
      jsOpt.fold(deserializationError("Missing string")) { js =>
        unbuilder.readString(js) match {
          case "disabled"     => TestingMode.Disabled
          case "compile-only" => TestingMode.CompileOnly
          case "full"         => TestingMode.Full
        }
      }
  }

  implicit object ProjectOverridesFormat extends JsonFormat[ProjectOverrides] {
    def write[J](obj: ProjectOverrides, builder: Builder[J]): Unit = ???
    def read[J](jsOpt: Option[J], unbuilder: Unbuilder[J]): ProjectOverrides =
      jsOpt.fold(deserializationError("Empty object")) { js =>
        unbuilder.beginObject(js)
        val testsMode = unbuilder.readField[Option[TestingMode]]("tests")
        unbuilder.endObject
        ProjectOverrides(tests = testsMode)
      }
  }

  implicit object ProjectsConfigFormat extends JsonFormat[ProjectsConfig] {
    def write[J](obj: ProjectsConfig, builder: Builder[J]): Unit = ???
    def read[J](jsOpt: Option[J], unbuilder: Unbuilder[J]): ProjectsConfig =
      jsOpt.fold(deserializationError("Empty object")) { js =>
        unbuilder.beginObject(js)
        val excluded = unbuilder.readField[Array[String]]("exclude")
        val overrides = unbuilder.readField[Map[String, ProjectOverrides]]("overrides")
        unbuilder.endObject()
        ProjectsConfig(excluded.toList, overrides)
      }
  }

  implicit object ProjectBuildConfigFormat extends JsonFormat[ProjectBuildConfig] {
    def write[J](v: ProjectBuildConfig, builder: Builder[J]): Unit = ???
    def read[J](optValue: Option[J], unbuilder: Unbuilder[J]): ProjectBuildConfig =
      optValue.fold(deserializationError("Empty object")) { v =>
        unbuilder.beginObject(v)
        val projects = unbuilder.readField[ProjectsConfig]("projects")
        val testsMode = unbuilder.readField[TestingMode]("tests")
        unbuilder.endObject()
        ProjectBuildConfig(projects, testsMode)
      }
  }
}
