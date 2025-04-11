#!/usr/bin/env scala shebang

import java.nio.file.{Path, Files}
import java.time.LocalDate
import scala.sys.process.*

@main def scheduleCustom(repository: String, branch: String, args: String*) = 
  var executeTests = false 
  var pushToGHPages = false
  var sourceVersion = Option.empty[String]
  var scalacOptions = List.empty[String]
  args.foreach{
    case s"--executeTests" => executeTests = true
    case s"--gh-pages" => pushToGHPages = true
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
  repository match {
    case s"$repo/$project" => println(s"Build $repo/$project branch: $branch")
    case _ => sys.error(s"Invalid repository: $repository")
  }
  val date = LocalDate.now()

  for build <- Seq("A", "B")
  do {   
    val sourceVersionAttr = sourceVersion.map("-source:" + _).getOrElse("")
    val task =   
    s"""gh workflow run .github/workflows/buildExecuteCustom-$build.yaml 
    | -f build-name=${repository}:${branch}${sourceVersionAttr}:$date
    | -f execute-tests=${executeTests}
    | -f repository-url=${repository}
    | -f repository-branch=${branch}
    | -f extra-scalac-options=${scalacOptions.mkString(",")}
    | -f push-to-gh-pages=${pushToGHPages}
    |""".stripMargin
    
    println(s"Eval: $task")
    assert(task.linesIterator.mkString(" ").! == 0, "Failed to eval task")
  } 
