//> using scala "3"
//> using dep "com.sksamuel.elastic4s:elastic4s-client-esjava_2.13:8.11.5"
//> using dep "org.slf4j:slf4j-simple:2.0.13"
//> using toolkit 0.4.0
//> using options -Wunused:all

import com.sksamuel.elastic4s
import elastic4s.*
import elastic4s.http.JavaClient
import elastic4s.ElasticDsl.*
import elastic4s.requests.searches.*

import org.elasticsearch.client.RestClientBuilder.HttpClientConfigCallback
import org.apache.http.impl.nio.client.*
import org.apache.http.impl.client.BasicCredentialsProvider
import org.apache.http.auth.*

import scala.concurrent.*
import scala.concurrent.duration.*
import org.elasticsearch.client.RestClient
import org.apache.http.HttpHost

import scala.jdk.StreamConverters.*
import scala.util.chaining.*

given ExecutionContext = ExecutionContext.global

import upickle.default.*


final val IgnoredExceptions = Set(
  "Scala3CommunityBuild$ProjectBuildFailureException",
)
final val IgnoredExceptionMessages = Set(
  "Conflicting cross-version suffixes",
  "Compilation failed",
  "is broken, reading aborted with class dotty.tools.tasty.UnpickleException",
  "Not found: type",
  "is not a member of"
)
final val IgnoredTasks = Set(
  "compileIncremental",
  "compile",
  "doc"
)

final val LogsDir =  os.pwd / "logs"

val showIgnoredLogLines = false
val parseWarnings = true
val showNewErrors = true
val showDiffErrors = false
val listSameErrorsProjects = true
val listNewErrorsProjects = true
val listDiffErrorsProjects = true


val BuildSummariesIndex = "project-build-summary"
val DefaultTimeout = 5.minutes
val ElasticsearchHost = "scala3.westeurope.cloudapp.azure.com"
val ElasticsearchUrl = s"https://$ElasticsearchHost/data/"
val ElasticsearchCredentials = new UsernamePasswordCredentials(
  sys.env.getOrElse("ES_USERNAME", "elastic"),
  sys.env.getOrElse("ES_PASSWORD", "changeme")
)
lazy val esClient = {
  val clientConfig = new HttpClientConfigCallback {
    override def customizeHttpClient(
        httpClientBuilder: HttpAsyncClientBuilder
    ): HttpAsyncClientBuilder = {
      val creds = new BasicCredentialsProvider()
      creds
        .setCredentials(AuthScope.ANY, ElasticsearchCredentials)
      httpClientBuilder
        .setDefaultCredentialsProvider(creds)
    }
  }

  ElasticClient(
    JavaClient.fromRestClient(
      RestClient
        .builder(HttpHost(ElasticsearchHost, -1, "https"))
        .setPathPrefix("/data")
        .setHttpClientConfigCallback(clientConfig)
        .build()
    )
  )
}

given scala.util.CommandLineParser.FromString[os.Path] = path =>
  try os.Path(path)
  catch { case _: Exception => os.RelPath(path).resolveFrom(os.pwd) }



@main def raport(version: String) = try {
  os.remove.all(LogsDir)
  val nowFailing = failedProjectsForScalaVersion(version)
  printErrorStats(nowFailing, version)  
  // os.write.over(os.pwd / "output.txt", failedProjects.map(_.show).mkString("\n"))
  // os.write.over(os.pwd / "output.json", upickle.default.write(failedProjects.toArray, 2, escapeUnicode = true))

  val comparedScalaVersion = "3.3.3"
  val previouslyFailing =  failedProjectsForScalaVersion(comparedScalaVersion)
  printErrorStats(previouslyFailing, comparedScalaVersion)

  printErrorComparsionForVersions(nowFailing, previouslyFailing)
} finally esClient.close()
  

opaque type TASTyVersion = String
object TASTyVersion:
  given Writer[TASTyVersion] = upickle.default.StringWriter
  def apply(major: Int, minor: Int, unstableRelease: Option[Int]): TASTyVersion = s"$major.$minor" + unstableRelease.map(_ => "-unstable").getOrElse("")
case class FailedProject(name: String, errors: List[Error], warnings: List[Warning], scalaVersion: String, buildURL: Option[String]) derives Writer

enum Error derives Writer:
  case MissingDependency(dependency: String)
  case IncompatibleTASTyVersion(expected: TASTyVersion, found: TASTyVersion)
  case CompilationCrash(stacktrace: List[String])
  case CompilationError(code: Option[Int], kind: String, sourceFile: String, line: Int, column: Int, message: String, source: Option[String], explained: Option[String])
  case BuildTaskFailure(message: String)
  case UnhandledException(message: String,  stacktrace: List[String])
  case Misconfigured(message: String)


object Error:
  extension(self: CompilationError) 
    def sourcePosition = s"${self.sourceFile}:${self.line}:${self.column}"

enum Warning derives Writer:
    case CompilationWarning(code: Option[Int], kind: String, sourceFile: String, line: Int, column: Int, message: String, source: Option[String], explained: Option[String])

object Warning:
  extension(self: CompilationWarning) 
    def sourcePosition = s"${self.sourceFile}:${self.line}:${self.column}"


given Show[Error.CompilationError] = err => s"""Error${err.code.map("[" + _ + "]").getOrElse("")} ${err.kind}
  | - source file: ${err.sourceFile}:${err.line}:${err.column}
  | - source: ${err.source.map("\n" + _).getOrElse("<none>")} 
  | - messsage: ${err.message}
  """.stripMargin

given Show[Error] = _ match
  case err: Error.CompilationError => err.show
  case err: Error.CompilationCrash => "Compilation crash: \n" + err.stacktrace.mkString("\n\t- ")
  case err: Error.IncompatibleTASTyVersion => s"Incompatible TASTy version, expected ${err.expected}, found ${err.found}"
  case err: Error.MissingDependency => s"Missing external depenendcy: ${err.dependency}"
  case err: Error.BuildTaskFailure => s"Misconfigured build: ${err.message}"
  case err: Error.UnhandledException => s"Unhandled exception ${err.message}\n" + err.stacktrace.mkString("\n\t- ")
  case err: Error.Misconfigured => s"Misconfigured: ${err.message}"

given Show[FailedProject] = v =>
  s"""project: ${v.name}
     |scalaVersion=${v.scalaVersion}
     |warnings: ${v.warnings.size}
     |errors: 
     |${v.errors.zipWithIndex.map((err, idx) => s"- $idx. ${err.show}}").map(_.linesIterator.map("\t\t" + _).mkString("\n")).mkString("\n")}
  """.stripMargin 

def printErrorStats(failedProjects: Iterable[FailedProject], scalaVersion: String) = {
  println("###############")
  println(s"Database contains reports for ${failedProjects.size} failed projects using Scala $scalaVersion")
  failedProjects.flatMap(_.errors)
    .tap:errors =>
      println(s"Found ${errors.size} errors:")
    .groupBy:
      case err: Error.CompilationError => err.getClass().getSimpleName() + " - " + err.kind
      case err => err.getClass().getSimpleName()
    .view.mapValues(_.size)
    .toSeq.sortBy(_._1)
    .foreach: (errorKind, count) =>
      println(s"  - ${errorKind}:\t$count")
  println("###############")
}

def printErrorComparsionForVersions(nowFailing: Set[FailedProject], previouslyFailing: Set[FailedProject]) = {
  def printLine() = println("-" * 16)
  val newFailures = nowFailing.map(_.name).diff(previouslyFailing.map(_.name))
  println(s"New failures in ${newFailures.size} projects")
  newFailures.toSeq.sorted.zipWithIndex.foreach: (project, idx) => 
    val url = nowFailing.find(_.name == project).flatMap(_.buildURL).getOrElse("")
    println(s"$idx.\t$project - $url")
  val allNewErrors = nowFailing.flatMap(_.errors).diff(previouslyFailing.flatMap(_.errors))
  printLine()
  println(s"New errors: ${allNewErrors.size}")

  val alreadyFailing = nowFailing.map(_.name).intersect(previouslyFailing.map(_.name))
  println(s"Projects previously failing: ${alreadyFailing.size}")

  case class ProjectComparsion(name: String,  previous: FailedProject, current: FailedProject, comparsion: Comparsion)
  case class ErrorsDiff(sourcePosition: Option[String], previous: Seq[Error], current: Seq[Error])
  case class Comparsion(sameErrors: Seq[Error], newErrors: Seq[Error], diffErrors: Seq[ErrorsDiff])
  val projectComparsion = alreadyFailing.map: project =>
    val current = nowFailing.find(_.name == project).get
    val prev = previouslyFailing.find(_.name == project).get
    val newErrors = current.errors.diff(prev.errors).filter:
        case err: Error.CompilationError => !prev.errors.exists:
          case prev: Error.CompilationError => err.sourcePosition == prev.sourcePosition 
          case _ => false
        case _ => true
      .sortBy(_.ordinal)
    def otherErrors(errors: Seq[Error]) = errors.filterNot(_.isInstanceOf[Error.CompilationError])
    ProjectComparsion(name = project, 
      previous = prev,
      current = current,
      Comparsion(
        sameErrors = current.errors.intersect(prev.errors).sortBy(_.ordinal),
        newErrors  = newErrors,
        diffErrors = ((current.errors.diff(prev.errors) ++ prev.errors.diff(current.errors))).collect:
            case err: Error.CompilationError => err
          .groupBy(_.sourcePosition)
          .filter(_._2.size > 1)
          .toSeq
          .map: (sourcePosition, compilationErrors) => 
            ErrorsDiff(sourcePosition = Some(sourcePosition),
             current = current.errors.intersect(compilationErrors),
             previous = prev.errors.intersect(compilationErrors)
            )
          .appended: 
            ErrorsDiff(sourcePosition = None, 
              current = otherErrors(newErrors),
              previous = otherErrors(prev.errors).diff(otherErrors(newErrors))
            ) 
          .filter(diff => diff.previous.nonEmpty && diff.current.nonEmpty)
          .sortBy(_.sourcePosition)
      ))
  val (withSameErrors, withDiffErrors) = projectComparsion.partition: project => 
    project.comparsion.diffErrors.isEmpty && project.comparsion.newErrors.isEmpty

  println(s"Projects with the same errors: ${withSameErrors.size}")
  if listSameErrorsProjects && withSameErrors.nonEmpty then
    withSameErrors.toSeq.sortBy(_.name).zipWithIndex.foreach: (p, idx) =>
      println(s"$idx: ${p.name}: ${p.current.errors.size} same errors")
    printLine()

  val diffErrorsProjects = withDiffErrors.filter(_.comparsion.diffErrors.nonEmpty)
  println(s"Projects with different errors: ${diffErrorsProjects.size}")
  if listDiffErrorsProjects && diffErrorsProjects.nonEmpty then
    diffErrorsProjects.toSeq.sortBy(_.name).zipWithIndex.foreach: (p, idx) =>
      println(s"$idx: ${p.name}: ${p.comparsion.diffErrors.size} different errors ${p.current.buildURL.zip(p.previous.buildURL).map("- " + _ + " vs " + _).getOrElse("")}")
    printLine()

  val newErrorsProjects = withDiffErrors.filter(_.comparsion.newErrors.nonEmpty)
  println(s"Projects with new errors: ${withDiffErrors.filter(_.comparsion.newErrors.nonEmpty).size}")
  if listNewErrorsProjects && newErrorsProjects.nonEmpty then
    newErrorsProjects.toSeq.sortBy(_.name).zipWithIndex.foreach: (p, idx) =>
      println(s"$idx: ${p.name}: ${p.comparsion.newErrors.size} new errors ${p.current.buildURL.map("- " + _).getOrElse("")}")
    printLine()
  
  if showNewErrors || showDiffErrors then {
    withDiffErrors.toSeq.sortBy(_.name).zipWithIndex.foreach{ (p, idx) =>    
      println(s"$idx: ${p.name}: ${p.comparsion.diffErrors.size} diff errors, ${p.comparsion.newErrors.size} new errors")
      if showNewErrors && p.comparsion.newErrors.nonEmpty then
        println(s"New errors: ${p.comparsion.newErrors.size}")
        p.comparsion.newErrors.zipWithIndex.foreach: (err, idx) =>
          println(s"* $idx. ${err.show}")
        printLine()

      if showDiffErrors && p.comparsion.diffErrors.nonEmpty then
        println(s"Different errors: ${p.comparsion.diffErrors.size}")
        p.comparsion.diffErrors.zipWithIndex.foreach: (diff, idx) =>
          println(s"$idx. Compilation error at ${diff.sourcePosition}")
          println(s"Previously [${diff.previous.size}]")
          diff.previous.map(" * " + _.show).foreach(println)
          println(s"Currently [${diff.current.size}]")
          diff.current.map(" * " + _.show).foreach(println)
          printLine()
       printLine()
    }
  }
}

def parseErrorLogs(logs: String): List[Error] = {
  var logLine = 0
  var isParsingError = false

  logs.lines().toScala(LazyList)
    .tapEach(_ => logLine += 1)
    .foldLeft(List.empty[Error]){
      case (acc, msg @ s"[error] ${_} [E${code}] ${kind}: ${sourceFile}:${line}:${tail}") =>
        val column = tail.takeWhile(_.isDigit)
        isParsingError = true
        Error.CompilationError(code = Some(code.toInt), kind = kind, sourceFile = sourceFile, line = line.toInt, column = column.toInt, message = "",source = None, explained = None) :: acc

      case (acc, msg @ s"[error] ${_} ${kind}: ${sourceFile}:${line}:${tail}") if sourceFile.endsWith(".scala")  =>
        val column = tail.takeWhile(_.isDigit)
        isParsingError = true
        Error.CompilationError(code = None, kind = kind, sourceFile = sourceFile, line = line.toInt, column = column.toInt, message = "",source = None, explained = None) :: acc

      case (acc, s"[error] ## Exception when compiling ${_} sources ${_}") => 
        isParsingError = true
        Error.CompilationCrash(stacktrace = Nil) :: acc

      case (acc, s"[error] TASTy signature has wrong version${_}") => 
        isParsingError = true
        Error.IncompatibleTASTyVersion("", "") :: acc

      case (acc, line @ (
          s"[error]${_} is not a valid choice for ${_}" |
          s"[error]${_} invalid choice(s) for ${_}"
        )) => 
        Error.Misconfigured(s"Invalid setting: ${line.stripPrefix("[error]").trim()}") :: acc

      case (acc, s"[error]${_}sbt.librarymanagement.ResolveException: Error downloading ${dependency}") =>
        Error.MissingDependency(dependency) :: acc

      case (acc, s"[error] ($module / $scope / $task) ${msg}") =>
        if IgnoredExceptions.exists(msg.contains) || IgnoredTasks.contains(task) then acc
        else Error.BuildTaskFailure(s"Task failed: ${module}/$scope/$task : $msg") :: acc
      case (acc, s"[error] ($module / $task) ${msg}")  =>
        if IgnoredExceptions.exists(msg.contains) || IgnoredTasks.contains(task) then acc
        else Error.BuildTaskFailure(s"Task failed: ${module}/$task : $msg") :: acc
 
      case (acc @ (last :: tail), line @ s"[error] ${_}") if isParsingError => {
        last match
          case last: Error.CompilationError =>
            line match {
              case s"[error] ${sourceLine}|${source}" if sourceLine.trim().toIntOption.contains(last.line) =>
                last.copy(source = Some(source)) :: tail
              case s"[error] ${_}|Explanation (enabled by `-explain`)" | s"[error] Explanation" =>
                last.copy(explained = Some("")) :: tail
              case s"[error]${_}|${message}" if !message.isBlank() => 
                val updated = 
                  if message.trim().forall(_ == '^') then 
                    last.copy(source = last.source.map(_ + "\n" + message))
                  else if last.explained.isDefined then
                    last.copy(explained = last.explained.map(_ + message + "\n"))
                  else 
                    last.copy(message = last.message + "\n" + message) 
                updated :: tail 
              case s"[error] ${message}" if !message.isBlank() && last.explained.isDefined => 
                val updated = last.copy(explained = last.explained.map(_ + message + "\n"))
                updated :: tail 
              case msg =>
                if showIgnoredLogLines then println(s"Ignored [${logLine}] compilation error: ${msg}")
                acc
            }
          case last: Error.CompilationCrash => line match {
            case s"[error] ${msg}" =>
              last.copy(stacktrace = last.stacktrace :+ msg.trim()) :: tail
            case msg =>
              if showIgnoredLogLines then println(s"Ignored crash log: ${logLine}: ${msg}")
              acc
          }
          case last: Error.IncompatibleTASTyVersion => {
            val TASTyVersionExtractor = raw"\{majorVersion: (\d+), minorVersion: (\d)( \[unstable release: (\d+)\])?\}".r
            line.trim() match
              case s"[error] ${expectedOrFound}: ${TASTyVersionExtractor(major, minor, _, unstable)}" => 
                val tastyVersion = TASTyVersion(major.toInt, minor.toInt, Option(unstable).flatMap(_.toIntOption))
                val updated = expectedOrFound.trim() match
                  case "expected" => last.copy(expected = tastyVersion)
                  case "found" => last.copy(found = tastyVersion)
                updated :: tail
              case _ => 
                if showIgnoredLogLines then println(s"Ignore incompatible tasty: ${logLine}: $line")
                acc
          }
          case last: Error.UnhandledException => line match {
             case s"[error] ${msg}" => 
              last.copy(stacktrace = last.stacktrace :+ msg.trim()) :: tail
            case _ => acc
          }
          case _: Error.MissingDependency => acc
          case _: Error.BuildTaskFailure => acc 
          case _: Error.Misconfigured => acc
        }
 
      case (acc, s"[error] ${exception}") 
      if Seq("Exception", "Error", "Failure", "Failed").exists{ suffix =>
          exception.split(" ")
            .map(_.trim().filter(_.isLetterOrDigit))
            .exists(part => part.endsWith(suffix) && part != suffix)
        } => 
        if IgnoredExceptions.exists(exception.contains) || IgnoredExceptionMessages.exists(exception.contains) then acc
        else Error.UnhandledException(exception, Nil) :: acc

      case (acc, s"[error]${padding}|${body}") if padding.isBlank() && body.isBlank() =>
        acc
        
      case (acc , s"[error]${msg}")  => 
        if showIgnoredLogLines && msg.filter(_.isLetterOrDigit).size > 3 then
            println(s"Ignored ${logLine}: ${msg}")
        acc
      case (acc, _) => 
        isParsingError = false
        acc
  }
  .distinctBy{
    case err: Error.CompilationError => (err.line, err.column, err.code, err.kind)
    case Error.CompilationCrash(stacktrace) => stacktrace.mkString
    case Error.IncompatibleTASTyVersion(expected, found) => (expected, found)
    case Error.BuildTaskFailure(message) => message
    case Error.MissingDependency(dependency) => dependency
    case Error.UnhandledException(message, stacktrace) => (message, stacktrace.mkString)
    case Error.Misconfigured(message) => (message)
  }
  .sortBy(_.ordinal)
}

def parseWarningLogs(logs: String): List[Warning] = {
  var logLine = 0
  var isParsingWarning = false

  logs.lines().toScala(LazyList)
    .tapEach(_ => logLine += 1)
    .foldLeft(List.empty[Warning]){
      case (acc, msg @ s"[warn] ${_} [E${code}] ${kind}: ${sourceFile}:${line}:${tail}") =>
        val column = tail.takeWhile(_.isDigit)
        isParsingWarning = true
        Warning.CompilationWarning(code = Some(code.toInt), kind = kind, sourceFile = sourceFile, line = line.toInt, column = column.toInt, message = "",source = None, explained = None) :: acc

      case (acc, msg @ s"[warn] ${_} ${kind}: ${sourceFile}:${line}:${tail}") if sourceFile.endsWith(".scala")  =>
        val column = tail.takeWhile(_.isDigit)
        isParsingWarning = true
        Warning.CompilationWarning(code = None, kind = kind, sourceFile = sourceFile, line = line.toInt, column = column.toInt, message = "",source = None, explained = None) :: acc

      // case (acc, s"[error] ($module / $scope / $task) ${msg}") =>
      //   if IgnoredExceptions.exists(msg.contains) || IgnoredTasks.contains(task) then acc
      //   else Error.BuildTaskFailure(s"Task failed: ${module}/$scope/$task : $msg") :: acc
      // case (acc, s"[error] ($module / $task) ${msg}")  =>
      //   if IgnoredExceptions.exists(msg.contains) || IgnoredTasks.contains(task) then acc
      //   else Error.BuildTaskFailure(s"Task failed: ${module}/$task : $msg") :: acc
 
      case (acc @ (last :: tail), line @ s"[warn] ${_}") if isParsingWarning => {
        last match
          case last: Warning.CompilationWarning =>
            line match {
              case s"[warn] ${sourceLine}|${source}" if sourceLine.trim().toIntOption.contains(last.line) =>
                last.copy(source = Some(source)) :: tail
              case s"[warn] ${_}|Explanation (enabled by `-explain`)" | s"[error] Explanation" =>
                last.copy(explained = Some("")) :: tail
              case s"[warn]${_}|${message}" if !message.isBlank() => 
                val updated = 
                  if message.trim().forall(_ == '^') then 
                    last.copy(source = last.source.map(_ + "\n" + message))
                  else if last.explained.isDefined then
                    last.copy(explained = last.explained.map(_ + message + "\n"))
                  else 
                    last.copy(message = last.message + "\n" + message) 
                updated :: tail 
              case s"[warn] ${message}" if !message.isBlank() && last.explained.isDefined => 
                val updated = last.copy(explained = last.explained.map(_ + message + "\n"))
                updated :: tail 
              case msg =>
                if showIgnoredLogLines then println(s"Ignored [${logLine}] compilation error: ${msg}")
                acc
            }
        }
 
      case (acc, s"[warn]${padding}|${body}") if padding.isBlank() && body.isBlank() =>
        acc
        
      case (acc , s"[warn]${msg}")  => 
        if showIgnoredLogLines && msg.filter(_.isLetterOrDigit).size > 3 then
            println(s"Ignored ${logLine}: ${msg}")
        acc
      case (acc, _) => 
        isParsingWarning = false
        acc
  }
  .distinctBy{
    case err: Warning.CompilationWarning => (err.line, err.column, err.code, err.kind)
  }
  .sortBy(_.ordinal)
}


def passingProjectsForScalaVersion(scalaVersion: String) = 
  esClient
    .execute:
      search(BuildSummariesIndex)
        .query:
          boolQuery().must(
            termQuery("scalaVersion", scalaVersion),
            termQuery("status", "success")
        )
        .size(10 * 1000)
        .sourceInclude("projectName")
    .await(DefaultTimeout)
    .fold(
      err => sys.error(s"Failed to list projects from Elasticsearch: ${err.error}"),
      result => 
        result.hits.hits.map: hit =>
          hit.sourceField("projectName").asInstanceOf[String]
    )

def failedProjectsForScalaVersion(scalaVersion: String) = 
   esClient
    .execute:
      search(BuildSummariesIndex)
        .query:
          boolQuery().must(
            termQuery("scalaVersion", scalaVersion),
            termQuery("status", "failure"),
            not(
              termsQuery("projectName", passingProjectsForScalaVersion(scalaVersion).toSeq)
            )
          )
        .size(10 * 1000)
    .await(DefaultTimeout)
    .fold(
      err => sys.error(s"Failed to list projects from Elasticsearch: ${err.error}"),
      result => 
        result.hits.hits.filter: hit =>
          hit.sourceField("summary").asInstanceOf[List[Map[String, Map[String, String]]]]
            .exists: summary =>
              summary.get("compile").flatMap(_.get("status")).contains("failed") 
              || summary.get("test-compile").flatMap(_.get("status")).contains("failed")
        .map: hit =>
          FailedProject(
            name = hit.sourceField("projectName").asInstanceOf[String],
            errors = parseErrorLogs(hit.sourceField("logs").asInstanceOf[String]),
            warnings = 
              if parseWarnings then parseWarningLogs(hit.sourceField("logs").asInstanceOf[String])
              else Nil,
            scalaVersion = hit.sourceField("scalaVersion").asInstanceOf[String],
            buildURL = hit.sourceFieldOpt("buildURL").map(_.toString())
          ).tap: p => 
            if p.errors.isEmpty then 
              println(s"\nNo errors parsed in ${p.name}")
              val logDir = LogsDir / os.RelPath(p.name)
              os.makeDir.all(logDir)
              os.write.over(logDir / s"${p.scalaVersion}.log", hit.sourceField("logs").asInstanceOf[String])
        .toSet
    )


trait Show[T]:
  extension (v: T) def show: String 
