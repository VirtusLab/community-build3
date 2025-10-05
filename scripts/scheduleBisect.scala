#!/usr/bin/env scala shebang
//> using file ./scalaVersions.scala

import java.nio.file.Path
import java.nio.file.Files
import java.time.LocalDate

@main def scheduleBisect(projectName: String, args: String*) = 
  println(s"Schedule bisect of $projectName")
  var minScalaVersion = "3.4"
  var maxScalaVersion = Option.empty[String]
  var refreshConfig = false
  args.foreach{
    case s"--scalaVersionStart=$version" => minScalaVersion = version
    case s"--scalaVersionEnd=$version" => maxScalaVersion = Some(version) 
    case s"--refreshConfig" => refreshConfig = true
  }
 
  val configPath = Path.of("./.github/workflows/buildConfig.json").toAbsolutePath()
  assert(Files.exists(configPath))
  import scala.sys.process.*
  def configEntry(jqSelector: String): String = {
    s"""jq -c -r '."$projectName"$jqSelector' ${configPath}""".!!.trim().ensuring(_ != "null", s"Not entry for $projectName in config")
  } 
  
  if refreshConfig then {
    println(s"Refreshing config for $projectName")
    println(s"""scala run coordinator -- 3 1 1 1 "$projectName" ./coordinator/configs/""".!!)
  }
    
  val scalaVersionStart = versions.Releases.find(_.startsWith(minScalaVersion)).getOrElse(s"Not version with prefix $minScalaVersion")
  val scalaVersionEnd = maxScalaVersion.map: maxVersion => 
    versions.Releases.findLast(_.startsWith(maxVersion))
      .getOrElse(s"Not version with prefix $maxVersion")
  
  val task =   
  s"""gh workflow run .github/workflows/buildBisect.yaml 
  | -f build-name=${projectName}-${minScalaVersion}..${maxScalaVersion.getOrElse("latest")}-${LocalDate.now()}
  | -f project-name=${{configEntry(".project")}}
  | -f project-revision=${{configEntry(".revision")}}
  | -f scala-version-start=${scalaVersionStart}
  | -f scala-version-end=${scalaVersionEnd.getOrElse("")}
  |""".stripMargin
  
  println(s"Eval: $task")
  assert(task.linesIterator.mkString(" ").! == 0, "Failed to eval task")
  
