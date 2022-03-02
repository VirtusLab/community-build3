import sbt._
import sbt.Keys._

case class CommunityBuildCoverage(allDeps: Int, overridenScalaJars: Int, notOverridenScalaJars: Int)

abstract class BuildStepResult(val asString: String)

case class Info(msg: String) extends BuildStepResult(s""""$msg"""")
object CompileOk extends BuildStepResult(""""compile": "ok"""")
object CompileFailed extends BuildStepResult(""""compile": "failed"""")
object TestOk extends BuildStepResult(""""test": "ok"""")
object TestFailed extends BuildStepResult(""""test": "failed"""")
object PublishSkipped extends BuildStepResult(""""publish": "skipped"""")
case class PublishWrongVersion(version: Option[String]) extends BuildStepResult(s""""publish": "wrongVersion=${version}"""")
object PublishOk extends BuildStepResult(""""publish": "ok"""")
object PublishFailed extends BuildStepResult(""""publish": "failed"""")
case class BuildError(msg: String) extends BuildStepResult(s""""error": "${msg}"""")

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
  
  val ourResolver = "Community Build Repo" at sys.env("CB_MVN_REPO_URL")

  override def projectSettings = Seq(
    publishResultsConf := 
      publishM2Configuration.value
        .withPublishMavenStyle(true)
        .withResolverName(ourResolver.name),
    publishResults := Classpaths.publishTask(publishResultsConf).value,
    externalResolvers := ourResolver +: externalResolvers.value
  )

  private def stripScala3Suffix(s: String) = s match { case WithExtractedScala3Suffix(prefix, _) => prefix; case _ => s }

  /**
   * Helper command used to set correct version for publishing Defined due to a
   * bug in sbt which does not allow for usage of `set every` with scoped keys
   * We need to use this task instead of `set every Compile/version := ???`,
   * becouse it would set given value also in Jmh/version or Jcstress/version
   * scopes, leading build failures
   */
  val setPublishVersion = Command.args("setPublishVersion", "<args>") { case (state, args) =>
    args.headOption
      .orElse(sys.props.get("communitybuild.version"))
      .fold {
        System.err.println("No explicit version found in setPublishVersion command, skipping")
        state
      } { version =>
        println(s"Setting publish version to $version")

        val structure                      = sbt.Project.extract(state).structure
        def setVersionCmd(project: String) = s"""set $project/version := "$version" """

        structure.allProjectRefs.collect {
          // Filter out root project, we need to use ThisBuild instead of root project name
          case ref @ ProjectRef(uri, project) if structure.rootProject(uri) != project => ref
        }.foldLeft(Command.process(setVersionCmd("ThisBuild"), state)) { case (state, ref) =>
          // Check if project needs explicit overwrite, skip otherwise 
          val s              = sbt.Project.extract(state).structure
          val projectVersion = (ref / Keys.version).get(s.data).get
          if (projectVersion == version) state
          else Command.process(setVersionCmd(ref.project), state)
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
      IO.write(file("community-build-mappings.txt"), moduleIds.map(v => v._1 +" " +v._2.project).mkString("\n"))
    },
    runBuild := {
      try {
        val ids = spaceDelimited("<arg>").parsed.toList
        val cState = state.value
        val extracted = sbt.Project.extract(cState)
        val s = extracted.structure
        val refs = s.allProjectRefs
        val refsByName = s.allProjectRefs.map(r => r.project -> r).toMap
        val scalaBinaryVersionUsed = (extracted.currentRef / scalaBinaryVersion).get(s.data).get
        val scalaBinaryVersionSuffix = "_" + scalaBinaryVersionUsed
        val scalaVersionSuffix = "_" + (extracted.currentRef / scalaVersion).get(s.data).get

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
            case projects => projects
              .find(projectSupportsScalaBinaryVersion)
              .getOrElse(sys.error(s"Multiple targets found for ${target}, failed to select a single project that can be used with Scala ${scalaBinaryVersionUsed} in ${projects.map(_.project)}"))
          }
        }

        val originalModuleIds: Map[String, ProjectRef] =  IO.readLines(file("community-build-mappings.txt"))
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
            actualId = id + scalaVersionSuffix
            candidates = for {
              suffix <-
                Seq("", scalaVersionSuffix, scalaBinaryVersionSuffix) ++
                  Option("Dotty").filter(_ => scalaBinaryVersionUsed.startsWith("3"))
              fullId = s"$id$suffix"
            } yield Stream(
              refsByName.get(fullId),
              originalModuleIds.get(fullId),
              moduleIds.get(fullId),
              simplifiedModuleIds.get(simplifiedModuleId(fullId))
            ).flatten
          } yield candidates.flatten.headOption.getOrElse {
            println("Module mapping missing:")
            println(s"id: $id")
            println(s"actualId: $actualId")
            println(s"scalaVersionSuffix: $scalaVersionSuffix")
            println(s"scalaBinaryVersionSuffix: $scalaBinaryVersionSuffix")
            println(s"refsByName: $refsByName")
            println(s"originalModuleIds: $originalModuleIds")
            println(s"moduleIds: $moduleIds")
            throw new Exception("Module mapping missing")
          
          }
        ).toSet

        val projectDeps = s.allProjectPairs.map {case (rp, ref) =>
          ref -> rp.dependencies.map(_.project)
        }.toMap

        @annotation.tailrec def flatten(soFar: Set[ProjectRef], toCheck: Set[ProjectRef]): Set[ProjectRef] =
          toCheck match {
            case e if e.isEmpty => soFar
            case pDeps =>
              val deps = projectDeps(pDeps.head).filterNot(soFar.contains)
              flatten(soFar ++ deps, pDeps.tail ++ deps)
          }

        val allToBuild = flatten(topLevelProjects, topLevelProjects)

        println("Projects:")
        println(allToBuild)

        val projectsBuildResults = allToBuild.map { r =>
          println(s"Starting build for $r...")
          
          val k = r / Compile / compile
          // val t = r / Test / test
          val t = r / Test / compile

          // we should reuse state here

          val res = List.newBuilder[BuildStepResult]
          res += Info(r.project)

          try {
            sbt.Project.runTask(k, cState) match {
              case Some((_, Value(_))) =>
                res += CompileOk
                sbt.Project.runTask(t, cState) match {
                  case Some((_, Value(_))) => 
                    res += TestOk
                  case _ =>
                    res += TestFailed
                }

                ourVersion match {
                  case None =>
                    res += PublishSkipped
                  case Some(vo) =>
                    val cv = (r / version).get(s.data)
                    if (cv != Some(vo)){
                      res += PublishWrongVersion(cv)
                    } else {
                      val p = r / Compile / publishResults
                      sbt.Project.runTask(p, cState) match {
                        case Some((_, Value(_))) => 
                          res += PublishOk
                        case _ =>
                          res += PublishFailed
                      }
                    }
                }
              case _ =>
                res += CompileFailed
            } 
          } catch {
            case a: Throwable =>
              a.printStackTrace()
              res += BuildError(a.getMessage)
          }
          res.result
        }
        val buildSummary = projectsBuildResults.map{
          case subProjectName :: results => subProjectName.asString + ": " + results.map(_.asString).mkString("{", ", ", "}")
          case List(s) => s.asString 
        }.mkString("{", ", ", "}")
        println("************************")
        println("Build summary:")
        println(buildSummary)
        println("************************")
        IO.write(file("..") / "build-summary.txt", buildSummary)
        val failedSteps = projectsBuildResults.flatMap { results =>
          results.collect {
            case error @ (
              CompileFailed | TestFailed | PublishWrongVersion(_) | PublishFailed | BuildError(_)
            ) => error
          }
        }
        val buildStatus = if (failedSteps.isEmpty) "success" else "failure"
        IO.write(file("..") / "build-status.txt", buildStatus)
        if(failedSteps.nonEmpty) {
          throw new ProjectBuildFailureException
        }
      } catch {
        case a: Throwable =>
          a.printStackTrace()
          throw a
      }
    },
    (runBuild / aggregate) := false
   )
}
