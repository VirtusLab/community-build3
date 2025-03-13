#!/usr/bin/env scala shebang

import java.nio.file.{Path, Files}
import java.time.LocalDate
import scala.sys.process.*

@main def scheduleCustom(scalaVersion: String, args: String*) = 
  var sourceVersion = Option.empty[String]
  var scalacOptions = List.empty[String]
  args.foreach{
    case s"--sourceVersion=${version}" => 
      version match {
        case s"$major.$minor" => // ok
        case s"$major.$minor-migration" => // ok
        case "future" | "future-migration" => // ok 
        case _ => sys.error(s"Invalid sourceVersion $version")
      }
      scalacOptions ::= s"REQUIRE:-source:$version"
      sourceVersion = Some(version)
  }
  val date = LocalDate.now()

  for build <- Seq("A", "B")
  do {   
    val sourceVersionAttr = sourceVersion.map("-source:" + _).getOrElse("")
    val task =   
    s"""gh workflow run .github/workflows/buildExecuteCustom-$build.yaml 
    | -f build-name=${scalaVersion}${sourceVersionAttr}:$date
    | -f published-scala-version=${scalaVersion}
    | -f execute-tests=true
    | -f extra-scalac-options=${scalacOptions.mkString(",")}
    | -f push-to-gh-pages=true
    |""".stripMargin
    
    println(s"Eval: $task")
    assert(task.linesIterator.mkString(" ").! == 0, "Failed to eval task")
  } 
