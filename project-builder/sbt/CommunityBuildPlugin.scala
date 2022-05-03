import sbt._
import sbt.Keys._
import sjsonnew._, LList.:*:
import sjsonnew.BasicJsonProtocol._
import sjsonnew.support.scalajson.unsafe.{Converter, Parser}
import sbt.protocol.testing.TestResult
import xsbti.compile.{FileAnalysisStore, CompileAnalysis}
import xsbti.{Severity, CompileFailed, Problem}
import scala.collection.JavaConverters._

import TaskEvaluator.EvalResult

class SbtTaskEvaluator(val project: ProjectRef, private var state: State)
    extends TaskEvaluator[TaskKey] {

  override def eval[T](task: TaskKey[T]): EvalResult[T] = {
    val evalStart = System.currentTimeMillis()
    val scopedTask = project / task
    sbt.Project
      .runTask(scopedTask, state)
      .fold[EvalResult[T]] {
        val reason = TaskEvaluator.UnkownTaskException(scopedTask.toString())
        EvalResult.Failure(reason :: Nil, 0)
      } { case (newState, result) =>
        val tookMs = (System.currentTimeMillis() - evalStart).toInt
        this.state = newState
        result.toEither match {
          case Right(value) => EvalResult.Value(value, tookMs)
          case Left(incomplete) =>
            val causes = getAllDirectCauses(incomplete)
            EvalResult.Failure(causes, tookMs)
        }
      }
  }

  private def getAllDirectCauses(incomplete: Incomplete): List[Throwable] = {
    @scala.annotation.tailrec
    def loop(incomplete: List[Incomplete], acc: List[Throwable] = Nil): List[Throwable] = {
      incomplete match {
        case Nil => acc
        case head :: tail =>
          loop(
            incomplete = tail ::: head.causes.toList,
            acc = acc ::: head.directCause.toList
          )
      }
    }
    loop(incomplete :: Nil, Nil)
  }
}

object WithExtractedScala3Suffix {
  def unapply(s: String): Option[(String, String)] = {
    val parts = s.split("_")
    if (parts.length > 1 && parts.last.startsWith("3")) {
      Some((parts.init.mkString("_"), parts.last))
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
  val setPublishVersion =
    Command.args("setPublishVersion", "<args>") { case (state, args) =>
      args.headOption
        .orElse(sys.props.get("communitybuild.version"))
        .filter(_.nonEmpty)
        .fold {
          System.err.println("No explicit version found in setPublishVersion command, skipping")
          state
        } { targetVersion =>
          println(s"Setting publish version to $targetVersion")
          val extracted = sbt.Project.extract(state)
          val updatedVersions = extracted.structure.allProjectRefs
            .map { ref =>
              (ref / Keys.version).transform(
                currentVersion => {
                  dualVersioning
                    .filter(_.matches(targetVersion, currentVersion))
                    .flatMap(_.apply(targetVersion))
                    .map { version =>
                      println(
                        s"Setting version ${version.render} for ${ref.project} based on dual versioning ${dualVersioning}"
                      )
                      version.render
                    }
                    .getOrElse(targetVersion)
                },
                sbt.internal.util.NoPosition
              )
            }
          extracted.appendWithSession(updatedVersions, state)
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
  lazy val dualVersioning = Utils.DualVersioningType.resolve
  lazy val publishVersions = ourVersion.toList.map { version =>
    version :: dualVersioning.flatMap(_.apply(version)).map(_.render).toList
  }

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
      println(s"Build config: ${configJson}")
      val config = {
        val parsed = Parser
          .parseFromString(configJson)
          .flatMap(Converter.fromJson[ProjectBuildConfig](_)(ProjectBuildConfigFormat))
        println(s"Parsed config: ${parsed}")
        parsed.getOrElse(ProjectBuildConfig())
      }

      val cState = state.value
      val extracted = sbt.Project.extract(cState)
      val s = extracted.structure
      val refs = s.allProjectRefs
      val refsByName = s.allProjectRefs.map(r => r.project -> r).toMap
      val scalaBinaryVersionUsed = CrossVersion.binaryScalaVersion(scalaVersionArg)
      val scalaBinaryVersionSuffix = "_" + scalaBinaryVersionUsed
      val scalaVersionSuffix = "_" + scalaVersionArg
      val rootDirName = file(".").getCanonicalFile().getName()

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

      val filteredIds = Utils.filterTargets(ids, config.projects.exclude.map(_.r))

      println("Starting build...")
      // Find projects that matches maven
      val topLevelProjects = {
        var haveUsedRootModule = false
        val idsWithMissingMappings = scala.collection.mutable.ListBuffer.empty[String]
        val mappedProjects = for {
          id <- filteredIds
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
        } yield {
          candidates.flatten.headOption
            .orElse {
              // Try use root project, it might not be explicitly declared or named
              refsByName
                .get(rootDirName)
                .collect {
                  case rootProject if !haveUsedRootModule =>
                    haveUsedRootModule = true
                    rootProject
                }
            }
            .orElse {
              println(s"""Module mapping missing:
                |  id: $id
                |  testedIds: $testedFullIds
                |  scalaVersionSuffix: $scalaVersionSuffix
                |  scalaBinaryVersionSuffix: $scalaBinaryVersionSuffix
                |  refsByName: ${refsByName.keySet}
                |  originalModuleIds: ${originalModuleIds.keySet}
                |  moduleIds: ${moduleIds.keySet}
                |""".stripMargin)
              idsWithMissingMappings += id
              None
            }
        }

        if (idsWithMissingMappings.nonEmpty) {
          throw new Exception(
            s"Module mapping missing for: ${idsWithMissingMappings.toSeq.mkString(", ")}"
          )
        }
        mappedProjects.flatten.toSet
      }

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

      val projectsBuildResults = allToBuild.toList.map { r =>
        val evaluator = new SbtTaskEvaluator(r, cState)
        val s = sbt.Project.extract(cState).structure
        val projectName = (r / moduleName).get(s.data).get
        println(s"Starting build for $r ($projectName)...")

        val overrideSettings = {
          val overrides = config.projects.overrides
          overrides
            .get(projectName)
            .orElse {
              overrides.collectFirst {
                // No Regex.matches in Scala 2.12
                // Exclude cases when excluded name is a prefix of other projects
                case (key, value)
                    if key.r.findFirstIn(projectName).isDefined &&
                      !projectName.startsWith(key) =>
                  value
              }
            }
        }
        val testingMode = overrideSettings.flatMap(_.tests).getOrElse(config.tests)

        import evaluator._
        val compileResult = eval(Compile / compile)
        val docsResult = evalAsDependencyOf(compileResult)(Compile / doc)
        val testsCompileResult = evalWhen(testingMode != TestingMode.Disabled, compileResult)(
          Test / compile
        )
        val testsExecuteResult = evalWhen(testingMode == TestingMode.Full, testsCompileResult)(
          Test / executeTests
        )

        val publishResult = ourVersion.fold(PublishResult(Status.Skipped, tookMs = 0)) { version =>
          val currentVersion = (r / Keys.version)
            .get(s.data)
            .getOrElse(sys.error(s"${r.project}/version not set"))
          if (publishVersions.contains(currentVersion))
            PublishResult(
              Status.Failed,
              failureContext = Some(
                FailureContext.WrongVersion(expected = version, actual = currentVersion)
              ),
              tookMs = 0
            )
          else
            PublishResult(
              evalAsDependencyOf(compileResult, docsResult)(
                Compile / publishResults
              )
            )
        }

        ModuleBuildResults(
          artifactName = projectName,
          compile = collectCompileResult(compileResult),
          doc = DocsResult(docsResult),
          testsCompile = collectCompileResult(testsCompileResult),
          testsExecute = collectTestResults(testsExecuteResult),
          publish = publishResult
        )
      }

      val buildSummary = BuildSummary(projectsBuildResults)
      println(s"""
          |************************
          |Build summary:
          |${buildSummary.toJson}
          |************************""".stripMargin)
      IO.write(file("..") / "build-summary.txt", buildSummary.toJson)

      val failedModules = projectsBuildResults
        .filter(_.hasFailedStep)
        .map(_.artifactName)
      val hasFailedSteps = failedModules.nonEmpty
      val buildStatus =
        if (hasFailedSteps) "failure"
        else "success"
      IO.write(file("..") / "build-status.txt", buildStatus)
      if (hasFailedSteps) throw new ProjectBuildFailureException(failedModules)
    },
    (runBuild / aggregate) := false
  )

  private def collectCompileResult(evalResult: EvalResult[CompileAnalysis]): CompileResult = {
    def countProblems(severity: Severity, problems: Iterable[Problem]) =
      problems.count(_.severity == severity)
    evalResult match {
      case EvalResult.Failure(reasons, tookTime) =>
        reasons
          .collectFirst { case ctx: CompileFailed => ctx }
          .foldLeft(
            CompileResult(
              Status.Failed,
              failureContext = evalResult.toBuildError,
              warnings = 0,
              errors = 0,
              tookMs = tookTime
            )
          ) { case (result, ctx) =>
            result.copy(
              warnings = countProblems(Severity.Warn, ctx.problems()),
              errors = countProblems(Severity.Error, ctx.problems())
            )
          }

      case EvalResult.Value(compileAnalysis, tookTime) =>
        case class CompileStats(warnings: Int, errors: Int)
        val stats = compileAnalysis
          .readSourceInfos()
          .getAllSourceInfos()
          .asScala
          .values
          .foldLeft(CompileStats(0, 0)) { case (CompileStats(accWarnings, accErrors), sourceInfo) =>
            def countAll(severity: Severity) =
              countProblems(severity, sourceInfo.getReportedProblems()) +
                countProblems(severity, sourceInfo.getUnreportedProblems())

            CompileStats(
              warnings = accWarnings + countAll(Severity.Warn),
              errors = accErrors + countAll(Severity.Error)
            )
          }
        CompileResult(
          Status.Ok,
          warnings = stats.warnings,
          errors = stats.errors,
          tookMs = tookTime
        )

      case EvalResult.Skipped => CompileResult(Status.Skipped, warnings = 0, errors = 0, tookMs = 0)
    }
  }

  def collectTestResults(evalResult: EvalResult[sbt.Tests.Output]): TestsResult = {
    val empty = TestsResult(
      evalResult.toStatus,
      failureContext = evalResult.toBuildError,
      passed = 0,
      failed = 0,
      ignored = 0,
      skipped = 0,
      tookMs = evalResult.evalTime
    )
    evalResult match {
      case EvalResult.Value(value, _) =>
        val initialState = empty.copy(status = value.overall match {
          case TestResult.Passed => Status.Ok
          case _                 => Status.Failed
        })
        value.events.values.foldLeft(initialState) { case (state, result) =>
          state.copy(
            passed = state.passed + result.passedCount,
            failed = state.failed + result.failureCount + result.errorCount + result.canceledCount,
            ignored = state.ignored + result.ignoredCount,
            skipped = state.skipped + result.skippedCount
          )
        }

      case _ => empty
    }
  }

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
        implicit val _unbuilder: Unbuilder[J] = unbuilder
        unbuilder.beginObject(js)
        val testsMode = readOrDefault("tests", Option.empty[TestingMode])
        unbuilder.endObject
        ProjectOverrides(tests = testsMode)
      }
  }

  implicit object ProjectsConfigFormat extends JsonFormat[ProjectsConfig] {
    def write[J](obj: ProjectsConfig, builder: Builder[J]): Unit = ???
    def read[J](jsOpt: Option[J], unbuilder: Unbuilder[J]): ProjectsConfig =
      jsOpt.fold(deserializationError("Empty object")) { js =>
        implicit val _unbuilder: Unbuilder[J] = unbuilder
        unbuilder.beginObject(js)
        val excluded = readOrDefault("exclude", Array.empty[String])
        val overrides = readOrDefault("overrides", Map.empty[String, ProjectOverrides])
        unbuilder.endObject()
        ProjectsConfig(excluded.toList, overrides)
      }
  }

  implicit object ProjectBuildConfigFormat extends JsonFormat[ProjectBuildConfig] {
    def write[J](v: ProjectBuildConfig, builder: Builder[J]): Unit = ???
    def read[J](optValue: Option[J], unbuilder: Unbuilder[J]): ProjectBuildConfig =
      optValue.fold(deserializationError("Empty object")) { v =>
        implicit val _unbuilder: Unbuilder[J] = unbuilder
        unbuilder.beginObject(v)
        val projects = readOrDefault("projects", ProjectsConfig())
        val testsMode = readOrDefault[TestingMode, J]("tests", TestingMode.Full)
        unbuilder.endObject()
        ProjectBuildConfig(projects, testsMode)
      }
  }
  private def readOrDefault[T, J](field: String, default: T)(implicit
      format: JsonReader[T],
      unbuilder: Unbuilder[J]
  ) =
    unbuilder
      .lookupField(field)
      .fold(default)(v => format.read(Some(v), unbuilder))
}
