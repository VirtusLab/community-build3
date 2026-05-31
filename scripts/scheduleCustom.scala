#!/usr/bin/env scala shebang

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import scala.sys.process.*

def parseGitHubBranchUrl(url: String): (repository: String, branch: String) =
  val pattern = """https?://(?:www\.)?github\.com/([^/]+)/([^/]+)/tree/(.+)""".r
  url.stripSuffix("/") match
    case pattern(owner, repo, branch) =>
      (s"$owner/$repo", URLDecoder.decode(branch, StandardCharsets.UTF_8))
    case _ =>
      sys.error(s"Invalid GitHub branch URL: $url (expected https://github.com/owner/repo/tree/branch)")

@main def scheduleCustom(githubUrl: String, args: String*) =
  val (repository, branch) = parseGitHubBranchUrl(githubUrl)
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
  println(s"Build $repository branch: $branch")
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
