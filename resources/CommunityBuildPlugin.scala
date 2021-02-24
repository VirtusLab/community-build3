import sbt._
import sbt.Keys._

object CommunityBuildPlugin extends AutoPlugin {
   override def trigger = allRequirements

  val runBuild = inputKey[Unit]("")

  import complete.DefaultParsers._

  lazy val overrides = 
    IO.readLines(file("..") / "deps.txt").filter(_.nonEmpty).map { l =>
      val d = l.split("%")
      d(0) % d(1) % d(2)
    }

  lazy val ourVersion = 
    Option(sys.props("communitybuild.version"))
  
  override def projectSettings = Seq(
    dependencyOverrides := overrides
  )

  override def globalSettings = Seq(
    runBuild := {
      try {
        val ids = spaceDelimited("<arg>").parsed.toList
        val cState = state.value
        val s = Project.extract(cState).structure
        val refs = s.allProjectRefs
        val moduleIds = refs.map { r =>
          val current: ModuleID = projectID.in(r).get(s.data).get
          current.organization +"%"+current.name -> r
        }.groupBy(_._1).map{ case (i, v) => i -> v.map(_._2)}.toMap

        println("Starting build...")

        val toBuild = for {
          id <- ids
          _ = println(moduleIds(id))
          r <- moduleIds(id) if !r.project.endsWith("Native") // TODO we should be more smarter here
        } yield { 
          println(s"Starting build for $r...")
          val coverries = dependencyOverrides.in(r).get(s.data)
          assert(Some(overrides) == coverries, s"overrides, expected:  $overrides but has $coverries")
          
          val k = r / Compile / compile
          val t = r / Test / test

          // we should reuse state here

          val res = List.newBuilder[String]
          res += id
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
                      val p = r / Compile / publishLocal
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