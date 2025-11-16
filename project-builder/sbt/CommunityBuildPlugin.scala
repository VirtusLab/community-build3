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

import Scala3CommunityBuild.{Utils => _, _}
import Scala3CommunityBuild.Utils.{LibraryDependency, logOnce}
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
    def loop(
        incomplete: List[Incomplete],
        acc: List[Throwable]
    ): List[Throwable] = {
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

  import complete.DefaultParsers._

  lazy val customMavenRepoRepository = sys.env
    .get("CB_MVN_REPO_URL")
    .filter(_.nonEmpty)
    .map("Community Build Repo".at(_))
    .toSeq

  private lazy val akkaRepos = sys.env
    .get("OPENCB_AKKA_REPO_TOKEN")
    .filter(_.nonEmpty)
    .toSeq
    .map { AkkaToken =>
      "akka-secure-mvn" at s"https://repo.akka.io/$AkkaToken/secure"
    }
  private lazy val scala3NightliesRepo = "The Scala Nightly Repository"
    .at("https://repo.scala-lang.org/artifactory/maven-nightlies/")

  private lazy val extraOpenCBMavenRepos =
    customMavenRepoRepository ++
      Seq(scala3NightliesRepo) ++
      akkaRepos

  override def projectSettings = Seq(
    externalResolvers := extraOpenCBMavenRepos ++ externalResolvers.value,
    // Fix for cyclic dependency when trying to use crossScalaVersion ~= ???
    crossScalaVersions := (thisProjectRef / crossScalaVersions).value,
    // Use explicitly required scala version, otherwise we might stumble onto the default projects Scala versions
    libraryDependencies ++= extraLibraryDependencies(
      buildScalaVersion = sys.props.get("communitybuild.scala").filter(_.nonEmpty),
      projectScalaVersion = (thisProjectRef / scalaVersion).value
    )
  )

  private def extraLibraryDependencies(
      buildScalaVersion: Option[String],
      projectScalaVersion: String
  ): Seq[ModuleID] =
    if (!projectScalaVersion.startsWith("3.")) Nil
    else
      Scala3CommunityBuild.Utils
        .extraLibraryDependencies(
          buildScalaVersion.getOrElse(projectScalaVersion)
        )
        .map {
          case LibraryDependency(
                org,
                artifact,
                version,
                scalaCrossVersion
              ) =>
            if (scalaCrossVersion) org %% artifact % version
            else org % artifact % version
        }

  private def stripScala3Suffix(s: String) = s match {
    case WithExtractedScala3Suffix(prefix, _) => prefix; case _ => s
  }

  val disableFatalWarnings =
    keyTransformCommand("disableFatalWarnings", Keys.scalacOptions) { (_, _) =>
      val flags = Seq("-Xfatal-warnings", "-Werror")
      (_: Scope, currentSettings: Seq[String]) => currentSettings.filterNot(flags.contains)
    }

  private val projectCrossScalaVersions = mutable.Map.empty[String, Seq[String]]

  /** Helper command used to update crossScalaVersion It's needed for sbt 1.7.x, which does force
    * exact match in `++ <scalaVersion>` command for defined crossScalaVersions,
    */
  val setCrossScalaVersions =
    projectBasedKeyTransformCommand(
      "setCrossScalaVersions",
      Keys.crossScalaVersions
    ) { (args, extracted) =>
      val scalaVersion = args.head
      val partialVersion = CrossVersion.partialVersion(scalaVersion)
      val targetsScala3 = partialVersion.exists(_._1 == 3)

      (ref: ProjectRef, currentCrossVersions: Seq[String]) => {
        val currentScalaVersion = extracted.get(ref / Keys.scalaVersion)
        if (!projectCrossScalaVersions.contains(ref.project)) {
          projectCrossScalaVersions(ref.project) = currentCrossVersions
            .diff(Seq(scalaVersion)) ++ // exclude current version
            sys.env
              .get("OVERRIDEN_SCALA_VERSION")
              .filterNot(_.isEmpty) // overriden before start of built tool
        }

        def updateVersion(fromVersion: String) = {
          logOnce(
            s"Changing crossVersion $fromVersion -> $scalaVersion in ${ref.project}/crossScalaVersions"
          )
          scalaVersion
        }
        def withCrossVersion(version: String) =
          (version -> CrossVersion.partialVersion(version))
        val crossVersionsWithPartial =
          currentCrossVersions.map(withCrossVersion).toMap
        val currentScalaWithPartial =
          Seq(currentScalaVersion).map(withCrossVersion).toMap
        val partialCrossVersions = crossVersionsWithPartial.values.toSet
        val allPartialVersions =
          crossVersionsWithPartial ++ currentScalaWithPartial

        type PartialVersion = Option[(Long, Long)]
        def mapVersions(versionsWithPartial: Map[String, PartialVersion]) =
          versionsWithPartial
            .map {
              case (version, Some((3, _))) if targetsScala3 =>
                updateVersion(version)
              case (version, `partialVersion`) => updateVersion(version)
              case (version, _)                => version // not changed
            }
            .toSeq
            .distinct

        allPartialVersions(currentScalaVersion) match {
          // Check currently used version of given project
          // Some projects only set scalaVersion, while leaving crossScalaVersions default, eg. softwaremill/tapir in xxx3, xxx2_13 projects
          case pv if partialCrossVersions.contains(pv) =>
            mapVersions(allPartialVersions)
          case _ => // if version is not a part of cross version allow only current version
            val allowedCrossVersions = mapVersions(currentScalaWithPartial)
            logOnce(
              s"Limitting incorrect crossVersions $currentCrossVersions -> $allowedCrossVersions in ${ref.project}/crossScalaVersions"
            )
            allowedCrossVersions
        }
      }
    }

  val mapScalacOptions =
    keyTransformCommand("mapScalacOptions", Keys.scalacOptions) {
      (args, extracted) => (scope: Scope, currentScalacOptions: Seq[String]) =>
        val scalaVersion = extracted.get(scope / Keys.scalaVersion)
        val safeArgs = args.map(_.split(",").toList.filter(_.nonEmpty))
        val append = safeArgs.lift(0).getOrElse(Nil)
        lazy val (appendScala3Exclusive, appendScala3Inclusive) =
          append.partition { opt =>
            scala3ExclusiveFlags.exists(opt.contains(_))
          }
        // Make sure to not modify Scala 2 project scalacOptions
        // these can compiled as transitive dependency of custom startup task
        val filteredAppend =
          if (scalaVersion.startsWith("3.")) append
          else {
            appendScala3Exclusive.foreach { setting =>
              logOnce(
                s"Exclude Scala3 specific scalacOption `$setting` in Scala ${scalaVersion} module $scope"
              )
            }
            appendScala3Inclusive
          }

        val remove = safeArgs.lift(1).getOrElse(Nil)
        val filteredRemove =
          if (scalaVersion.startsWith("3.")) remove
          else remove ++ appendScala3Exclusive

        Scala3CommunityBuild.Utils.mapScalacOptions(
          scalaVersion = Some(scalaVersion),
          current = currentScalacOptions,
          append = filteredAppend,
          remove = filteredRemove
        )
    }

  // format: off
  val scala3ExclusiveFlags = Seq(
    "-source", "-rewrite", "-from-tasty", "-experimental",
    "-new-syntax", "-old-syntax", "-indent", "-no-indent",
    "-print-tasty", "-print-lines", "-uniqid", "-semanticdb-text", "-semanticdb-target",
    "-coverage-out", "-explain-types", "-scalajs",
    "-Vprofile" ,"-Vprofile-details" ,"-Vprofile-sorted-by", "-Vrepl-max-print-characters", "-Vrepl-max-print-elements",
    "-Wimplausible-patterns",
    "-Xcheck-macros", "-Xignore-scala2-macros", "-Ximplicit-search-limit",
    "-Ximport-suggestion-timeout", "-Xmax-inlined-trees", "-Xmax-inlines",
    "-Xprint-diff", "-Xprint-diff-del", "-Xprint-inline", "-Xprint-suspension",
    "-Xrepl-disable-display", "-Xsemanticdb",
    "-Xtarget", "-Xunchecked-java-output-version",
    "-Xverify-signatures", "-Xwiki-syntax",
    "-Ycc-debug", "-Ycc-log", "-Ycc-new", "-Ycc-print-setup",
    "-Ycheck-all-patmat", "-Ycheck-constraint-deps", "-Ycheck-mods", "-Ycheck-reentrant",
    "-Ycompile-scala2-library", "-Ycook-comments", "-Ycook-docs",
    "-Ydebug-error", "-Ydebug-flags", "-Ydebug-macros", "-Ydebug-missing-refs", "-Ydebug-names", "-Ydebug-pos", "-Ydebug-trace", "-Ydebug-tree-with-id", "-Ydebug-unpickling",
    "-Ydetailed-stats", "-Ydrop-comments", "-Ydrop-docs", "-Ydump-sbt-inc",
    "-Yexplain-lowlevel", "-Yexplicit-nulls",
    "-Yforce-inline-while-typing", "-Yforce-sbt-phases", "-Yforce-sbt-phases.", "-Yfrom-tasty-ignore-list",
    "-Yinstrument", "-Yinstrument-defs",
    "-Ykind-projector", "-Ylegacy-lazy-vals",
    "-Yno-decode-stacktraces", "-Yno-deep-subtypes", "-Yno-double-bindings", "-Yno-enrich-error-messages", "-Yno-experimental",
    "-Yno-kind-polymorphism", "-Yno-patmat-opt",
    "-Youtput-only-tasty", "-Yplain-printer",
    "-Yprint-debug", "-Yprint-debug-owners", "-Yprint-level", "-Yprint-pos", "-Yprint-pos-syms", "-Yprint-syms", "-Yprint-tasty",
    "-Yread-docs", "-Yrecheck-test", "-Yrequire-targetName", "-Yretain-trees",
    "-Ysafe-init", "-Ysafe-init-global", "-Yscala2-unpickler", "-Ysemanticdb", "-Yshow-print-errors",
    "-Yshow-suppressed-errors", "-Yshow-tree-ids", "-Yshow-var-bounds", "-Ytest-pickler"  
 )
  // format: on
  import sbt.librarymanagement.InclExclRule
  val excludeLibraryDependency =
    keyTransformCommand(
      "excludeLibraryDependency",
      Keys.allExcludeDependencies
    ) { (args, extracted) => (scope, currentExcludeDependencies: Seq[InclExclRule]) =>
      val scalaVersion = extracted.get(scope / Keys.scalaVersion)
      val newRules = args
        .map(_.replace("{scalaVersion}", scalaVersion))
        .map {
          _.split(":").zipWithIndex.foldLeft(InclExclRule()) {
            case (rule, (org, 0))      => rule.withOrganization(org)
            case (rule, (name, 1))     => rule.withName(name)
            case (rule, (artifact, 2)) => rule.withArtifact(artifact)
            case (_, (unexpected, idx)) =>
              sys.error(s"unexpected argument $unexpected at idx: $idx")
          }
        }
      currentExcludeDependencies ++ newRules.filterNot(
        currentExcludeDependencies.contains
      )
    }
  val removeScalacOptionsStartingWith =
    keyTransformCommand("removeScalacOptionsStartingWith", Keys.scalacOptions) {
      (args, _) => (_, currentScalacOptions: Seq[String]) =>
        currentScalacOptions.filterNot(opt => args.exists(opt.startsWith))
    }

  val commands = Seq(
    disableFatalWarnings,
    setCrossScalaVersions,
    mapScalacOptions,
    excludeLibraryDependency,
    removeScalacOptionsStartingWith
  )

  type SettingMapping[T] = (Seq[String], Extracted) => (Scope, T) => T
  def keyTransformCommand[T](name: String, task: ScopedTaskable[T])(
      mapping: SettingMapping[T]
  ) =
    Command.args(name, "args") { case (state, args) =>
      println(s"Execute $name: ${args.mkString(" ")}")
      val extracted = sbt.Project.extract(state)
      import extracted._
      val withArgs = mapping(args, extracted)
      val r = sbt.Project.relation(extracted.structure, true)
      val allDefs = r._1s.toSeq
      val projectScopes =
        allDefs.filter(_.key == task.key).map(_.scope).distinct
      val globalScopes = Seq(Scope.Global)
      def reapply(scopes: Seq[Scope]): State = {
        val redefined = task match {
          case setting: SettingKey[T] =>
            scopes.map(scope => (scope / setting) ~= (v => withArgs(scope, v)))
          case task: TaskKey[T] =>
            scopes.map(scope => (scope / task) ~= (v => withArgs(scope, v)))
        }
        val session = extracted.session.appendRaw(redefined)
        BuiltinCommands.reapply(session, structure, state)
      }
      try reapply(globalScopes ++ projectScopes)
      catch {
        case ex: Throwable =>
          logOnce(
            s"Failed to reapply settings in $name: ${ex.getMessage}, retry without global scopes"
          )
          reapply(projectScopes)
      }
    }

  type ProjectBasedSettingMapping[T] =
    (Seq[String], Extracted) => (ProjectRef, T) => T
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

  override def globalSettings = Seq(
    resolvers ++= extraOpenCBMavenRepos,
    moduleMappings := { // Store settings in file to capture its original scala versions
      val moduleIds = mkMappings.value
      IO.write(
        file("community-build-mappings.txt"),
        moduleIds.map(v => v._1 + " " + v._2.project).mkString("\n")
      )
    },
    runBuild := {
      val scalaVersionArg :: configJson :: ids =
        spaceDelimited("<arg>").parsed.toList
      println(s"Build config: ${configJson}")
      val config = {
        val parsed = Parser
          .parseFromString(configJson)
          .flatMap(
            Converter.fromJson[ProjectBuildConfig](_)(ProjectBuildConfigFormat)
          )
        println(s"Parsed config: ${parsed}")
        parsed.getOrElse(ProjectBuildConfig())
      }

      val cState = state.value
      val extracted = sbt.Project.extract(cState)
      val s = extracted.structure
      val refsByName = s.allProjectRefs.map(r => r.project -> r).toMap
      val scalaBinaryVersionUsed =
        CrossVersion.binaryScalaVersion(scalaVersionArg)
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
          scalaBinaryVersionUsed.startsWith("3") && projectRef.project.contains(
            "Dotty"
          )
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
        case "*%*" :: _ =>
          originalModuleIds.keys.toSeq
            .filterNot { id => id.contains("_sjs") || id.contains("_native") }
        case ids => ids
      }
      val filteredIds = Scala3CommunityBuild.Utils
        .filterTargets(idsToUse, config.projects.exclude.map(_.r))

      println("Starting build...")
      // Find projects that matches maven
      val topLevelProjects = {
        var haveUsedRootModule = false
        val idsWithMissingMappings =
          scala.collection.mutable.ListBuffer.empty[String]
        val mappedProjects =
          for {
            id <- filteredIds
            testedSuffixes = Seq(
              "",
              scalaVersionSuffix,
              scalaBinaryVersionSuffix
            ) ++
              Option("Dotty").filter(_ => scalaBinaryVersionUsed.startsWith("3"))
            testedFullIds = testedSuffixes.map(id + _)
            candidates = for (fullId <- testedFullIds)
              yield Stream(
                refsByName.get(fullId),
                originalModuleIds.get(fullId),
                moduleIds.get(fullId),
                simplifiedModuleIds.get(simplifiedModuleId(fullId)),
                // Single, top level, unnamed project
                refsByName.headOption
                  .map(_._2)
                  .filter(_ => refsByName.size == 1)
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
              System.err.println(s"""Module mapping missing:
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
          val msg =
            s"Failed to resolve mappings for ${idsWithMissingMappings.size}:${filteredIds.size} targets: ${idsWithMissingMappings.toSeq
              .mkString(", ")}"
          if (idsWithMissingMappings.size >= filteredIds.size) sys.error(msg)
          else System.err.println(msg)
        }
        mappedProjects.flatten.toSet
      }

      val projectDeps = s.allProjectPairs.map { case (rp, ref) =>
        ref -> rp.dependencies.map(_.project)
      }.toMap

      @annotation.tailrec
      def flatten(
          soFar: Set[ProjectRef],
          toCheck: Set[ProjectRef]
      ): Set[ProjectRef] =
        toCheck match {
          case e if e.isEmpty => soFar
          case pDeps =>
            val deps = projectDeps(pDeps.head).filterNot(soFar.contains)
            flatten(soFar ++ deps, pDeps.tail ++ deps)
        }

      val allToBuild = flatten(topLevelProjects, topLevelProjects)
      println("Projects: " + allToBuild.map(_.project))

      val projectsBuildResults = allToBuild.toList.zipWithIndex.map { case (r, idx) =>
        val evaluator = new SbtTaskEvaluator(r, cState)
        val s = sbt.Project.extract(cState).structure
        val projectName = (r / moduleName).get(s.data).get
        println(s"Starting build for $r ($projectName)... [$idx/${allToBuild.size}]")

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
        val testingMode =
          overrideSettings.flatMap(_.tests).getOrElse(config.tests)

        import evaluator._
        val scalacOptions = eval(Compile / Keys.scalacOptions) match {
          case EvalResult.Value(settings, _) => settings
          case _                             => Nil
        }
        println(s"Compile scalacOptions: ${scalacOptions.mkString(", ")}")
        def mayRetry[T](task: TaskKey[T])(
            evaluate: TaskKey[T] => EvalResult[T]
        ): EvalResult[T] = evaluate(task) match {
          case EvalResult.Failure(reasons, _) if reasons.exists {
                case ex: AssertionError =>
                  ex.getMessage.contains("overlapping patches")
                case _ => false
              } =>
            evaluate(task)
          case result => result
        }
        val compileResult = mayRetry(Compile / compile)(eval)

        val shouldBuildDocs = eval(Compile / doc / skip) match {
          case EvalResult.Value(skip, _) => skip
          case _                         => false
        }
        val docsResult = mayRetry(Compile / doc) {
          evalWhen(shouldBuildDocs, compileResult)
        }

        val testsCompileResult = mayRetry(Test / compile) {
          evalWhen(testingMode != TestingMode.Disabled, compileResult)
        }
        // Introduced to fix publishing artifact locally in scala-debug-adapter
        lazy val testOptionsResult = eval(Test / testOptions)
        val testsExecuteResult =
          evalWhen(
            testingMode == TestingMode.Full,
            testsCompileResult,
            testOptionsResult
          )(
            Test / executeTests
          )

        val shouldPublish = eval(Compile / publish / skip) match {
          case EvalResult.Value(skip, _) => skip
          case _                         => false
        }
        val publishResult = PublishResult(
          evalWhen(shouldPublish, compileResult)(Compile / publishLocal)
        )

        ModuleBuildResults(
          artifactName = projectName,
          compile = collectCompileResult(compileResult, scalacOptions),
          doc = DocsResult(docsResult),
          testsCompile = collectCompileResult(testsCompileResult, scalacOptions),
          testsExecute = collectTestResults(
            testsExecuteResult,
            eval(Test / definedTests),
            eval(Test / loadedTestFrameworks)
          ),
          publish = publishResult,
          metadata = ModuleMetadata(
            crossScalaVersions = projectCrossScalaVersions.getOrElse(r.project, Nil)
          )
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

      case EvalResult.Skipped =>
        CompileResult(Status.Skipped, warnings = 0, errors = 0, tookMs = 0)
    }
  }

  def collectTestResults(
      evalResult: EvalResult[sbt.Tests.Output],
      definedTests: EvalResult[Seq[sbt.TestDefinition]],
      loadedTestFrameworks: EvalResult[
        Map[sbt.TestFramework, sbt.testing.Framework]
      ]
  ): TestsResult = {
    val default = TestsResult(
      evalResult.toStatus,
      failureContext = evalResult.toBuildError,
      tookMs = evalResult.evalTime
    )

    evalResult match {
      case EvalResult.Value(value, _) =>
        val status = value.overall match {
          case TestResult.Passed => Status.Ok
          case _                 => Status.Failed
        }
        def sum(results: Iterable[SuiteResult]) =
          results.foldLeft(TestStats.empty) { case (state, result) =>
            state.copy(
              passed = state.passed + result.passedCount,
              failed =
                state.failed + result.failureCount + result.errorCount + result.canceledCount,
              ignored = state.ignored + result.ignoredCount,
              skipped = state.skipped + result.skippedCount
            )
          }
        val byFrameworkStats: Map[String, TestStats] =
          (definedTests, loadedTestFrameworks) match {
            case (
                  EvalResult.Value(definedTests, _),
                  EvalResult.Value(loadedTestFrameworks, _)
                ) =>
              val frameworkByFingerprint = loadedTestFrameworks.values.flatMap { framework =>
                val name = framework.name()
                framework.fingerprints().map(_ -> name)
              }.toMap
              val testFrameworks = definedTests.map { test =>
                val framework = frameworkByFingerprint
                  .get(test.fingerprint)
                  .orElse {
                    // In case if overwrites toString but not equals (see munit)
                    frameworkByFingerprint.collectFirst {
                      case (fingerprint, name)
                          if test.fingerprint
                            .toString() == fingerprint.toString =>
                        name
                    }
                  }
                  .getOrElse("unknown")
                test.name -> framework
              }.toMap
              value.events
                .groupBy { case (testName, _) =>
                  testFrameworks.get(testName).getOrElse("unknown")
                }
                .map { case (frameworkName, results) =>
                  frameworkName -> sum(results.values)
                }
            case _ => Map.empty
          }
        val overallStats = sum(value.events.values)
        default.copy(
          status = status,
          overall = overallStats,
          byFramework = byFrameworkStats
        )
      case _ => default
    }
  }

  // Serialization
  implicit object TestingModeEnumJsonFormat extends JsonFormat[TestingMode] {
    def write[J](x: TestingMode, builder: Builder[J]): Unit = ???
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
        val overrides =
          readOrDefault("overrides", Map.empty[String, ProjectOverrides])
        unbuilder.endObject()
        ProjectsConfig(excluded.toList, overrides)
      }
  }

  implicit object ProjectBuildConfigFormat extends JsonFormat[ProjectBuildConfig] {
    def write[J](v: ProjectBuildConfig, builder: Builder[J]): Unit = ???
    def read[J](
        optValue: Option[J],
        unbuilder: Unbuilder[J]
    ): ProjectBuildConfig =
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
