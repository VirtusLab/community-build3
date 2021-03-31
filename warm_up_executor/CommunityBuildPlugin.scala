import sbt._
import sbt.Keys._
//import sbt.librarymanagement._

case class CommunityBuildCoverage(allDeps: Int, overridenScalaJars: Int, notOverridenScalaJars: Int)

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
    externalResolvers := {
      externalResolvers.value.map {
        case res if res.name == "public" => ourResolver
        case other => other
      }
    }
  )

  // Create mapping from org%artifact_name to project name
  val mkMappings = Def.task {
     val cState = state.value
      val s = Project.extract(cState).structure
      val refs = s.allProjectRefs
      refs.map { r =>
        val current: ModuleID = projectID.in(r).get(s.data).get
        val sv = scalaVersion.in(r).get(s.data).get
        val sbv = scalaBinaryVersion.in(r).get(s.data).get
        val name = CrossVersion(current.crossVersion, sv, sbv)
          .fold(current.name)(_(current.name))
        current.organization +"%"+name -> r
      }
  }

  lazy val ourVersion = 
    Option(sys.props("communitybuild.version"))

  override def globalSettings = Seq(
    moduleMappings := { // Store settings in file to capture its original scala versions
      val moduleIds = mkMappings.value
      IO.write(file("..") / "mapping.txt", moduleIds.map(v => v._1 +" " +v._2.project).mkString("\n"))
    },
    runBuild := {
      try {
        val ids = spaceDelimited("<arg>").parsed.toList
        val cState = state.value
        val extracted = Project.extract(cState)
        val s = extracted.structure
        val refs = s.allProjectRefs
        val refsByName = s.allProjectRefs.map(r => r.project -> r).toMap
        
        val svprefix = "_" + scalaBinaryVersion.in(extracted.currentRef).get(s.data).get

        val originalModuleIds: Map[String, ProjectRef] =  IO.readLines(file("..") / "mapping.txt")
          .map(_.split(' ')).map(d => d(0) -> refsByName(d(1))).toMap
        val moduleIds: Map[String, ProjectRef] = mkMappings.value.toMap

        println("Starting build...")
        println(originalModuleIds.map(pair => pair._1+"="+pair._2).mkString("?","&",""))

        // Find projects that matches maven
        val topLevelProjects = (
          for {
            id <- ids
            actualId = id + svprefix
          } yield originalModuleIds.getOrElse(actualId, moduleIds(actualId))
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

        val toBuild = allToBuild.map { r =>
          println(s"Starting build for $r...")
          
          val k = r / Compile / compile
          val t = r / Test / test

          // we should reuse state here

          val res = List.newBuilder[String]
          //res += id
          res += r.project

          try {
            Project.runTask(k, cState) match {
              case Some((_, Value(_))) =>
                res += "compile:ok"
                Project.runTask(t, cState) match {
                  case Some((_, Value(_))) => 
                    res += "test:ok"
                  case _ =>
                    res += "test:failed"  
                }

                ourVersion match {
                  case None =>
                    res += "publish:skipped"
                  case Some(vo) =>
                    val cv = version.in(r).get(s.data)
                    if (cv != Some(vo)){
                      res += "publish:wrongVersion=" + cv
                    } else {
                      val p = r / Compile / publishResults
                      Project.runTask(p, cState) match {
                        case Some((_, Value(_))) => 
                          res += "publish:ok"
                        case _ =>
                          res += "publish:failed"  
                      }
                    }
                }
              case _ =>
                res += "compile:failed"
            } 
          } catch {
            case a: Throwable =>
              a.printStackTrace()
              res += "error=" + a.getMessage
          }
          res.result.mkString(",\t")
        }
        IO.write(file("..") / "res.txt", toBuild.mkString("\n"))
      } catch {
        case a: Throwable =>
          a.printStackTrace()
          throw a
      }
    },
    aggregate.in(runBuild) := false
   )
}