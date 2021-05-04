import sbt._
import sbt.Keys._

case class CommunityBuildCoverage(allDeps: Int, overridenScalaJars: Int, notOverridenScalaJars: Int)

abstract class BuildStepResult(val asString: String)

case class Info(msg: String) extends BuildStepResult(msg)
object CompileOk extends BuildStepResult("compile:ok")
object CompileFailed extends BuildStepResult("compile:failed")
object TestOk extends BuildStepResult("test:ok")
object TestFailed extends BuildStepResult("test:failed")
object PublishSkipped extends BuildStepResult("publish:skipped")
case class PublishWrongVersion(version: Option[String]) extends BuildStepResult(s"publish:wrongVersion=${version}")
object PublishOk extends BuildStepResult("publish:ok")
object PublishFailed extends BuildStepResult("publish:failed")
case class BuildError(msg: String) extends BuildStepResult(s"error=${msg}")

class ProjectBuildFailureException extends Exception

object CommunityBuildPlugin extends AutoPlugin {
  override def trigger = allRequirements

  val runBuild = inputKey[Unit]("")
  val moduleMappings = inputKey[Unit]("")
  val publishResults = taskKey[Unit]("")
  val publishResultsConf = taskKey[PublishConfiguration]("")
  
  import complete.DefaultParsers._
  
  // TODO we should simply hide maven central...
  val ourResolver = ("proxy" at sys.env("serverLocation")).withAllowInsecureProtocol(true) 

  override def projectSettings = Seq(
    publishResultsConf := 
      publishConfiguration.value
        .withPublishMavenStyle(true)
        .withResolverName(ourResolver.name),
    publishResults := Classpaths.publishTask(publishResultsConf).value,
    // externalResolvers := {
    //   externalResolvers.value.map {
    //     case res if res.name == "public" => ourResolver
    //     case other => other
    //   }
    // }
    externalResolvers := ourResolver +: externalResolvers.value
  )

  // Create mapping from org%artifact_name to project name
  val mkMappings = Def.task {
     val cState = state.value
      val s = Project.extract(cState).structure
      val refs = s.allProjectRefs
      refs.map { r =>
        val current: ModuleID = (r / projectID).get(s.data).get
        val sv = (r / scalaVersion).get(s.data).get
        val sbv = (r / scalaBinaryVersion).get(s.data).get
        val name = CrossVersion(current.crossVersion, sv, sbv)
          .fold(current.name)(_(current.name))
        current.organization + "%" + name -> r
      }
  }

  lazy val ourVersion = 
    Option(sys.props("communitybuild.version"))

  override def globalSettings = Seq(
    moduleMappings := { // Store settings in file to capture its original scala versions
      val moduleIds = mkMappings.value
      IO.write(file("community-build-mappings.txt"), moduleIds.map(v => v._1 +" " +v._2.project).mkString("\n"))
    },
    runBuild := {
      try {
        val ids = spaceDelimited("<arg>").parsed.toList
        val cState = state.value
        val extracted = Project.extract(cState)
        val s = extracted.structure
        val refs = s.allProjectRefs
        val refsByName = s.allProjectRefs.map(r => r.project -> r).toMap

        val scalaBinaryVersionSuffix = "_" + (extracted.currentRef / scalaBinaryVersion).get(s.data).get
        val scalaVersionSuffix = "_" + (extracted.currentRef / scalaVersion).get(s.data).get

        val originalModuleIds: Map[String, ProjectRef] =  IO.readLines(file("community-build-mappings.txt"))
          .map(_.split(' ')).map(d => d(0) -> refsByName(d(1))).toMap
        val moduleIds: Map[String, ProjectRef] = mkMappings.value.toMap

        println("Starting build...")
        
        // Find projects that matches maven
        val topLevelProjects = (
          for {
            id <- ids
            actualId = id + scalaVersionSuffix
          } yield
            Seq(
              originalModuleIds.get(id + scalaVersionSuffix),
              originalModuleIds.get(id + scalaBinaryVersionSuffix),
              originalModuleIds.get(id),
              moduleIds.get(id + scalaVersionSuffix),
              moduleIds.get(id + scalaBinaryVersionSuffix),
              moduleIds.get(id)
            ).flatten.head
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

        val projectsBuildResults = allToBuild.map { r =>
          println(s"Starting build for $r...")
          
          val k = r / Compile / compile
          val t = r / Test / test

          // we should reuse state here

          val res = List.newBuilder[BuildStepResult]
          //res += id
          res += Info(r.project)

          try {
            Project.runTask(k, cState) match {
              case Some((_, Value(_))) =>
                res += CompileOk
                Project.runTask(t, cState) match {
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
                      Project.runTask(p, cState) match {
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
        val buildResultsText = projectsBuildResults.map(_.map(_.asString).mkString("\t")).mkString("\n")
        println("************************")
        println("Build summary:")
        println(buildResultsText)
        println("************************")
        IO.write(file("..") / "res.txt", buildResultsText)

        val failedSteps = projectsBuildResults.flatMap { results =>
          results.collect {
            case error @ (
              CompileFailed | TestFailed | PublishWrongVersion(_) | PublishFailed | BuildError(_)
            ) => error
          }
        }
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