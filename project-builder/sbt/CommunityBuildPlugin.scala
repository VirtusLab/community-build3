import sbt._
import sbt.Keys._
import sjsonnew._, LList.:*:
import sjsonnew.BasicJsonProtocol._
import sjsonnew.support.scalajson.unsafe.{Converter, Parser}
import sbt.protocol.testing.TestResult
import xsbti.compile.{CompileAnalysis, FileAnalysisStore}
import xsbti.{CompileFailed, Problem, Severity}
import scala.collection.JavaConverters._
import scala.collection.mutable

import Scala3CommunityBuild._
import Scala3CommunityBuild.Utils._
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
    val Limit = 10
    @scala.annotation.tailrec
    def loop(incomplete: List[Incomplete], acc: List[Throwable]): List[Throwable] = {
      incomplete match {
        case Nil                     => acc
        case _ if acc.length > Limit => acc
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
    .map("Community Build Repo".at(_))
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

  override def projectSettings =
    Seq(
      // Fix for cyclic dependency when trying to use crossScalaVersion ~= ???
      crossScalaVersions := (thisProjectRef / crossScalaVersions).value
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
    keyTransformCommand("setPublishVersion", Keys.version) { (args, _) =>
      val targetVersion = args.headOption
        .filter(_.nonEmpty)
        .orElse(sys.props.get("communitybuild.version"))
        .getOrElse {
          throw new RuntimeException("No explicit version found in setPublishVersion command")
        }
      (scope: Scope, currentVersion: String) =>
        dualVersioning
          .filter(_.matches(targetVersion, currentVersion))
          .flatMap(_.apply(targetVersion))
          .map { version =>
            println(
              s"Setting version ${version.render} for ${scope} based on dual versioning ${dualVersioning}"
            )
            version.render
          }
          .getOrElse(targetVersion)
    }

  val disableFatalWarnings =
    keyTransformCommand("disableFatalWarnings", Keys.scalacOptions) { (_, _) =>
      val flags = Seq("-Xfatal-warnings", "-Werror")
      (_: Scope, currentSettings: Seq[String]) => currentSettings.filterNot(flags.contains)
    }

  /** Helper command used to filter scalacOptions and set source migration flags
    */
  val enableMigrationMode = keyTransformCommand("enableMigrationMode", Keys.scalacOptions) {
    (args, extracted) =>
      val argSourceVersion = args.headOption.filter(_.nonEmpty)
      def resolveSourceVersion(scalaVersion: String) =
        CrossVersion.partialVersion(scalaVersion).collect {
          case (3, 0) => "3.0-migration"
          case (3, 1) => "3.0-migration"
          case (3, 2) => "3.2-migration"
          case (3, _) => "future-migration"
        }

      (scope: Scope, currentSettings: Seq[String]) => {
        val scalaVersion = extracted.get(scope / Keys.scalaVersion)
        if (!scalaVersion.startsWith("3.")) currentSettings
        else
          argSourceVersion
            .orElse(resolveSourceVersion(scalaVersion))
            .fold(currentSettings) { sourceVersion =>
              val newEntries = Seq(s"-source:$sourceVersion")
              println(s"Setting migration mode ${newEntries.mkString(" ")} in ${scope}")
              // -Xfatal-warnings or -Wconf:any:e are don't allow to perform -source update
              val filteredSettings =
                Seq("-rewrite", "-source", "-migration", "-Xfatal-warnings")
              currentSettings.filterNot { setting =>
                filteredSettings.exists(setting.contains(_)) ||
                setting.matches(".*-Wconf.*any:e")
              } ++ newEntries
            }
      }
  }

  /** Helper command used to update crossScalaVersion It's needed for sbt 1.7.x, which does force
    * exact match in `++ <scalaVersion>` command for defined crossScalaVersions,
    */
  val setCrossScalaVersions =
    projectBasedKeyTransformCommand("setCrossScalaVersions", Keys.crossScalaVersions) {
      (args, extracted) =>
        val scalaVersion = args.head
        val partialVersion = CrossVersion.partialVersion(scalaVersion)
        val targetsScala3 = partialVersion.exists(_._1 == 3)

        (ref: ProjectRef, currentCrossVersions: Seq[String]) => {
          val currentScalaVersion = extracted.get(ref / Keys.scalaVersion)
          def updateVersion(fromVersion: String) = {
            println(
              s"Changing crossVersion $fromVersion -> $scalaVersion in ${ref.project}/crossScalaVersions"
            )
            scalaVersion
          }
          def withCrossVersion(version: String) = (version -> CrossVersion.partialVersion(version))
          val crossVersionsWithPartial = currentCrossVersions.map(withCrossVersion).toMap
          val currentScalaWithPartial = Seq(currentScalaVersion).map(withCrossVersion).toMap
          val partialCrossVersions = crossVersionsWithPartial.values.toSet
          val allPartialVersions = crossVersionsWithPartial ++ currentScalaWithPartial

          type PartialVersion = Option[(Long, Long)]
          def mapVersions(versionsWithPartial: Map[String, PartialVersion]) = versionsWithPartial
            .map {
              case (version, Some((3, _))) if targetsScala3 => updateVersion(version)
              case (version, `partialVersion`)              => updateVersion(version)
              case (version, _)                             => version // not changed
            }
            .toSeq
            .distinct

          allPartialVersions(currentScalaVersion) match {
            // Check currently used version of given project
            // Some projects only set scalaVersion, while leaving crossScalaVersions default, eg. softwaremill/tapir in xxx3, xxx2_13 projects
            case pv if partialCrossVersions.contains(pv) => mapVersions(allPartialVersions)
            case _ => // if version is not a part of cross version allow only current version
              val allowedCrossVersions = mapVersions(currentScalaWithPartial)
              println(
                s"Limitting incorrect crossVersions $currentCrossVersions -> $allowedCrossVersions in ${ref.project}/crossScalaVersions"
              )
              allowedCrossVersions
          }
        }
    }

  val appendScalacOptions = keyTransformCommand("appendScalacOptions", Keys.scalacOptions) {
    (args, extracted) => (_: Scope, currentScalacOptions: Seq[String]) =>
      (currentScalacOptions ++ args).distinct
  }

  val removeScalacOptions = keyTransformCommand("removeScalacOptions", Keys.scalacOptions) {
    (args, extracted) => (_: Scope, currentScalacOptions: Seq[String]) =>
      currentScalacOptions.filterNot(args.contains)
  }

  import sbt.librarymanagement.InclExclRule
  val excludeLibraryDependency =
    keyTransformCommand("excludeLibraryDependency", Keys.allExcludeDependencies) {
      (args, extracted) => (scope, currentExcludeDependencies: Seq[InclExclRule]) =>
        val scalaVersion = extracted.get(scope / Keys.scalaVersion)
        val newRules = args
          .map(_.replace("{scalaVersion}", scalaVersion))
          .map {
            _.split(":").zipWithIndex.foldLeft(InclExclRule()) {
              case (rule, (org, 0))      => rule.withOrganization(org)
              case (rule, (name, 1))     => rule.withName(name)
              case (rule, (artifact, 2)) => rule.withArtifact(artifact)
              case (rule, (unexpected, idx)) =>
                sys.error(s"unexpected argument $unexpected at idx: $idx")
            }
          }
        currentExcludeDependencies ++ newRules.filterNot(currentExcludeDependencies.contains)
    }
  val removeScalacOptionsStartingWith =
    keyTransformCommand("removeScalacOptionsStartingWith", Keys.scalacOptions) {
      (args, _) => (_, currentScalacOptions: Seq[String]) =>
        currentScalacOptions.filterNot(opt => args.exists(opt.startsWith))
    }

  val commands = Seq(
    enableMigrationMode,
    disableFatalWarnings,
    setPublishVersion,
    setCrossScalaVersions,
    appendScalacOptions,
    removeScalacOptions,
    excludeLibraryDependency,
    removeScalacOptionsStartingWith
  )

  type SettingMapping[T] = (Seq[String], Extracted) => (Scope, T) => T
  def keyTransformCommand[T](name: String, task: ScopedTaskable[T])(mapping: SettingMapping[T]) =
    Command.args(name, "args") { case (state, args) =>
      println(s"Execute $name: ${args.mkString(" ")}")
      val extracted = sbt.Project.extract(state)
      import extracted._
      val withArgs = mapping(args, extracted)
      val r = sbt.Project.relation(extracted.structure, true)
      val allDefs = r._1s.toSeq
      val scopes = allDefs.filter(_.key == task.key).map(_.scope).distinct
      val redefined = task match {
        case setting: SettingKey[T] =>
          scopes.map(scope => (scope / setting) ~= (v => withArgs(scope, v)))
        case task: TaskKey[T] =>
          scopes.map(scope => (scope / task) ~= (v => withArgs(scope, v)))
      }
      val session = extracted.session.appendRaw(redefined)
      BuiltinCommands.reapply(session, structure, state)
    }

    type ProjectBasedSettingMapping[T] = (Seq[String], Extracted) => (ProjectRef, T) => T
    def projectBasedKeyTransformCommand[T](name: String, task: ScopedTaskable[T])(
        mapping: ProjectBasedSettingMapping[T]
    ) =
      Command.args(name, "args") { case (state, args) =>
        println(s"Execute $name: ${args.mkString(" ")}")
        val extracted = sbt.Project.extract(state)
        val withArgs = mapping(args, extracted)
        val refs = extracted.structure.allProjectRefs
        state.appendWithSession(
          task match {
            case setting: SettingKey[T] =>
              refs.map(ref => (ref / setting) ~= (v => withArgs(ref, v)))
            case task: TaskKey[T] =>
              refs.map(ref => (ref / task) ~= (v => withArgs(ref, v)))
          }
        )
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
  lazy val dualVersioning = DualVersioningType.resolve
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

      val idsToUse = ids match {
        case "*%*" :: _ => originalModuleIds.keys.toSeq
        case ids        => ids
      }
      val filteredIds = filterTargets(idsToUse, config.projects.exclude.map(_.r))

      println("Starting build...")
      // Find projects that matches maven
      val topLevelProjects = {
        var haveUsedRootModule = false
        val idsWithMissingMappings = scala.collection.mutable.ListBuffer.empty[String]
        val mappedProjects =
          for {
            id <- filteredIds
            testedSuffixes = Seq("", scalaVersionSuffix, scalaBinaryVersionSuffix) ++
              Option("Dotty").filter(_ => scalaBinaryVersionUsed.startsWith("3"))
            testedFullIds = testedSuffixes.map(id + _)
            candidates = for (fullId <- testedFullIds)
              yield Stream(
                refsByName.get(fullId),
                originalModuleIds.get(fullId),
                moduleIds.get(fullId),
                simplifiedModuleIds.get(simplifiedModuleId(fullId)),
                // Single, top level, unnamed project
                refsByName.headOption.map(_._2).filter(_ => refsByName.size == 1)
              ).flatten
          } yield candidates.flatten.headOption
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
        val scalacOptions = eval(Compile / Keys.scalacOptions) match {
          case EvalResult.Value(settings, _) => settings
          case _                             => Nil
        }
        val compileResult = eval(Compile / compile)

        val shouldBuildDocs = eval(Compile / doc / skip) match {
          case EvalResult.Value(skip, _) => !skip
          case _                         => false
        }
        val docsResult = evalWhen(shouldBuildDocs, compileResult)(Compile / doc)

        val testsCompileResult = evalWhen(testingMode != TestingMode.Disabled, compileResult)(
          Test / compile
        )
        // Introduced to fix publishing artifact locally in scala-debug-adapter
        lazy val testOptionsResult = eval(Test / testOptions)
        val testsExecuteResult =
          evalWhen(testingMode == TestingMode.Full, testsCompileResult, testOptionsResult)(
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
              evalAsDependencyOf(compileResult)(Compile / publishResults)
            )
        }

        ModuleBuildResults(
          artifactName = projectName,
          compile = collectCompileResult(compileResult, scalacOptions),
          doc = DocsResult(docsResult),
          testsCompile = collectCompileResult(testsCompileResult, scalacOptions),
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

  private def collectCompileResult(
      evalResult: EvalResult[CompileAnalysis],
      scalacOptions: Seq[String]
  ): CompileResult = {
    def countProblems(severity: Severity, problems: Iterable[Problem]) =
      problems.count(_.severity == severity)

    val sourceVersion = scalacOptions.collectFirst {
      case s if s.contains("-source:") => s.split(":").last
    }

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
              tookMs = tookTime,
              sourceVersion = sourceVersion
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
          tookMs = tookTime,
          sourceVersion = sourceVersion
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
