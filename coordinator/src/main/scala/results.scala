import collection.JavaConverters._
import java.nio.file._
import scala.sys.process._

case class Result(name: String, compiled: Int, test: Int, published: Int, failureMsg: Seq[String])

val cloneProblems = Seq("Repo was not clonned!")

def listResult(p: Path): Result =  
  val name = p.getFileName.getFileName.toString
  if !Files.exists(p.resolve("build-summary.txt")) then 
    Result(name, 0, 0, 0, cloneProblems)
  else  
    val lines = Files.readAllLines(p.resolve("build-summary.txt")).asScala
    val res = lines.map { l => 
      val name :: rest = l.split(", ").toList
      val compiled = if l.contains("compile:ok") then 1 else 0
      val tested = if l.contains("test:ok") then 1 else 0
      val published = if l.contains("publish:ok") then 1 else 0
      val failure = if l.contains(":failed") then List(l) else Nil
      Result(name, compiled, tested, published, failure)
    }
    res.foldLeft(Result(name, 0, 0, 0, Nil)){ (res, e) =>
        Result(name, 
        res.compiled + e.compiled, 
        res.test + e.test, 
        res.published + e.published, 
        res.failureMsg ++ e.failureMsg.map(_ => e.name) 
      )
    }

@main def resultSummary = 
  val ws = Paths.get("ws")
  val projects = Files.list(ws).iterator.asScala.toSeq
  val results = projects.map(listResult)

  val ignoredProblems = 
    Files.readAllLines(Paths.get("ignored_projects.txt")).asScala
      .map(_.split(':').head).toSet

  val s = results.map { r => 
    val status = r.failureMsg match
      case Nil if ignoredProblems.contains(r.name) => "OK - FIXED"
      case Nil => "OK"
      case problems if ignoredProblems.contains(r.name) => "IGNORED"
      case `cloneProblems` => "MISSING TAG"
      case scopes => "Failed " + scopes.size + " scopes"
      
    
    r.name + " " + status
  }
  
  println(s.mkString("\n"))

  val (ignored, someFailures) = results.filter(_.failureMsg.nonEmpty)
    .partition(r => ignoredProblems(r.name))
  val (missingTag, failed) = someFailures.partition(_.failureMsg == cloneProblems)  
  val succeded = results.count(_.failureMsg.isEmpty)
  

  println(s"${failed.size} failed, $succeded succeded and ${ignored.size} ignored. " +
    s"${missingTag.size} are missing proper release tags")

  println("\n\n Failed: " + failed.map(_.name).mkString(", "))
  
