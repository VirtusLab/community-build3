import collection.JavaConverters._
import java.nio.file._
import scala.sys.process._

case class Result(name: String, compiled: Int, test: Int, published: Int, failureMsg: Seq[String])

def listResult(p: Path): Result =  
  val name = p.getFileName.getFileName.toString
  if !Files.exists(p.resolve("res.txt")) then 
    Result(name, 0, 0, 0, Seq("Repo was not clonned!"))
  else  
    val lines = Files.readAllLines(p.resolve("res.txt")).asScala
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

  val s = results.map(r => r.name + " " + (if r.failureMsg.isEmpty then "OK" else "Failed " + r.failureMsg.size + " scopes"))

  println(s.mkString("\n"))

  val failed = results.count(_.failureMsg.isEmpty)
  val succeded = results.count(_.failureMsg.nonEmpty)

  println(s"$failed failed, $succeded succeded")
  
