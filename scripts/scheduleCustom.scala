#!/usr/bin/env scala shebang

import java.nio.file.{Path, Files}
import java.time.LocalDate
import scala.sys.process.*

@main def scheduleCustom(repository: String, branch: String, args: String*) = 
  var executeTests = false 
  args.foreach{
    case s"--executeTests" => executeTests = true
    
  }
  repository match {
    case s"$repo/$project" => println(s"Build $repo/$project branch: $branch")
    case _ => sys.error("Invalid repository: $repository")
  }
  val date = LocalDate.now()

  for build <- Seq("A", "B")
  do {   
    val task =   
    s"""gh workflow run .github/workflows/buildExecuteCustom-$build.yaml 
    | -f build-name=${repository}:${branch}:$date
    | -f execute-tests=${executeTests}
    | -f repository-url=${repository}
    | -f repository-branch=${branch}
    |""".stripMargin
    
    println(s"Eval: $task")
    assert(task.linesIterator.mkString(" ").! == 0, "Failed to eval task")
  } 
