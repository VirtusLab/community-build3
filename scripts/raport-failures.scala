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
import java.util.Locale

final val LogsDir =  os.pwd / "logs"

// val comparedScalaVersion = "3.5.0-RC6-bin-ef43053-SNAPSHOT" // 
val comparedScalaVersion = "3.3.3"
val onlyFailingProjects = false

val showIgnoredLogLines     = false
val showNewErrors           = false
val showDiffErrors          = false
val listSameErrorsProjects  = false
val listNewErrorsProjects   = false
val listDiffErrorsProjects  = false

val parseWarnings           = true
val showNewWarnings         = false
val showDiffWarnings        = false
val listSameWarningProjects = false
val listNewWarningProjects  = false
val listDiffWarningProjects = false
// Maximal number of lines that the error with the same source/message might differ
val warningMaxLinesDifference = Some(10)

val BuildSummariesIndex = "project-build-summary"
val DefaultTimeout = 5.minutes
val ElasticsearchHost = "scala3.westeurope.cloudapp.azure.com"
val ElasticsearchUrl = s"https://$ElasticsearchHost/data/"
val ElasticsearchCredentials = new UsernamePasswordCredentials(
  sys.env.getOrElse("ES_USERNAME", "elastic"),
  sys.env.getOrElse("ES_PASSWORD", "changeme")
)

final val IgnoredExceptions = Set(
  "Scala3CommunityBuild$ProjectBuildFailureException",
)
final val IgnoredExceptionMessages = Set(
  "Conflicting cross-version suffixes",
  "Compilation failed",
  "is broken, reading aborted with class dotty.tools.tasty.UnpickleException",
  "Not found: type",
  "is not a member of",
  // Project contains deps compiled with newer Scala compiler 
  "TASTy signature has wrong version",
  "Incompatible TASTy version",
  "is not a valid choice for -source"
)
final val IgnoredTasks = Set(
  "compileIncremental",
  "compile",
  "doc"
)

final val IgnoredWarningMessages = Set(
  "No DRI found for query:"
)

def cached[T :Writer :Reader](key: String)(body: => T): T = {
  val cacheFile = os.pwd / ".cache" / s"$key.json"
  if os.exists(cacheFile) && 
    (System.currentTimeMillis() - os.stat(cacheFile).mtime.toMillis()).millis <= 24.hour then 
    println(s"Using cached result: $cacheFile")
    read[T](os.read(cacheFile))
  else
    val result = body
    os.makeDir.all(cacheFile / os.up)
    os.write.over(cacheFile, write[T](result, sortKeys = true, indent = 2))
    result 
}
def projectResultsKey(scalaVersion: String) = s"results-$scalaVersion-failedOnly=$onlyFailingProjects"
object warnings:
  private def normalize(v: String) = v.toLowerCase(Locale.ROOT).replaceAll("\n", " ").trim()
  case class Category(messageOrPattern: String, name: Option[String] = None):
    def categoryName = name.getOrElse(messageOrPattern)
    private val messageNormalized = normalize(messageOrPattern)
    private val patternNormalized = s".*${messageNormalized}.*".r
    private[warnings] def matches(message: String) = 
      message.contains(messageNormalized) || patternNormalized.matches(message)
  def matching(message: String) = 
    val messageNormalized = warnings.normalize(message)
    all
    .find(_.matches(messageNormalized))
    .orElse:
      // println("\n" + messageNormalized + "\n")
      None

  lazy val all = List(
    Category("New anonymous class definition will be duplicated at each inline site"),
    Category("already has a member with the same name and compatible parameter types"),
    Category("Missing symbol position"),
    Category("cannot override .* in .*"),
    Category("Suspicious top-level unqualified call to"),
    Category("@nowarn annotation does not suppress any warnings"),
    Category("Infinite recursive call"),
    Category("Infinite loop in function body"),
    Category("values cannot be volatile"),
    Category("error overriding .* in .* of type .* no longer has compatible type"),
    Category("class .* differs only in case from .*"),
    Category("Ambiguous implicits .* in .* and .* in .* seem to be used to implement a local failure in order to negate an implicit search"),
    Category("Reference to .* is ambiguous. It is both defined in .* and inherited subsequently in .*"),
    Category("An inline given alias with a function value as right-hand side can significantly increase generated code size."),
    Category("unreducible application of higher-kinded type .* to wildcard arguments"),
    Category("This type test will never return a result since the scrutinee type .* does not contain any value"),
    Category("The package name .* will be encoded on the classpath, and can lead to undefined behaviour"),
    Category("should be an instance of Matchable"),
    Category("error overriding value .* lazy value .* of type .* may not override a non-lazy value"),
    Category("Could not emit switch for @switch annotated match"),
    Category("is more specialized than the right hand side expression's type"),
    Category("given is now a keyword"),
    Category("Pattern binding uses refutable extractor"),
    // patter matching
    Category("match may not be exhaustive") ,
    Category("Unreachable case"),
    Category("cannot be checked at runtime because its type arguments can't be determined from"),
    Category("cannot be checked at runtime because it refers to an abstract type member or type parameter"),
    Category("cannot be checked at runtime because it's a local class"),
    // deprecations
    Category("is deprecated") ,Category("will be deprecated") ,Category("has been deprecated"),
    Category("Non local returns are no longer supported"),
    Category("The syntax `x: _*` is no longer supported for vararg splices"),
    Category("`_*` can be used only for last argument of method application"),
    Category("The syntax `<function> _` is no longer supported"),
    Category("is not declared infix; it should not be used as infix operator"),
    Category("method .* is eta-expanded even though .* does not have the @FunctionalInterface annotation"),
    Category("must be called with () argument"),
    Category("symbol literal '.* is no longer supported"),
    Category("`extends` must be followed by at least one parent"),
    Category("Invalid message filter"), // TODO should be warning kind
    // linting
    Category("unused "),
    Category("Modifier .* is redundant for this definition"),
    Category("Discarded non-Unit value of type"),
    Category("unset local variable, consider using an immutable val instead"),
    Category("A pure expression does nothing in statement position"),
    Category("Line is indented too far to the "),
    Category("A try without catch or finally"),
    Category("Access non-initialized value "),
    Category("Promoting the value to transitively initialized (Hot) failed due to the following problem:"),
    Category("Unset local variable"),
    // migration warningss
    Category("Result of implicit search for .* will change"),
    Category("Context bounds will map to context parameters"),
    Category("According to new variance rules, this is no longer accepted; need to annotate with @uncheckedVariance"),
    Category("The conversion .* will not be applied implicitly here in Scala 3 because only implicit methods and instances of Conversion class will continue to work as implicit views"),
    Category("Type ascriptions after patterns other than:.* are no longer supported"),
    Category(".* in package .* has changed semantics in version"),
    Category("@SerialVersionUID does nothing on a trait"),
    // project specific
    Category("Found a problem with your DI wiring", Some("7mind/izumi specific")) ,
    Category("Pathological intersection refinement result in lambda being reconstructed ", Some("7mind/izumi specific")),
    Category("Cannot extract argument name from.*", Some("7mind/izumi specific")),
    Category("Unable to parse query", Some("quill specific")),
    Category("The non-inlined expression .* is forcing the query to become dynamic", Some("quill specific")),
    Category("Questionable row-class found.* The field .* will be used in the .* instead of the field .*", Some("quill specific")),
    Category("Query Was Static but a Dynamic Meta was found: .*.This has forced the query to become dynam", Some("quill specific")),
    Category("Generic type .* is not supported as.* member of sealed family", Some("play specific")),
    Category("cannot handle class .*: no case accessor", Some("anorm specific")),
    Category("The entry .* must be defined in the enum companion", Some("enumeratun specific")),
    Category("defaulting to foreach, can not optimise range expression", Some("metarank/ltrlib specific")),
  )
def warningsFilter(warning: Warning): Boolean = warning match {
  case Warning.CompilationWarning(code, kind, sourceFile, line, column, message, source, explained) => 
    true
    && !message.isBlank() 
    // && warnings.matching(message).isDefined
  case _ => false
}
def resultsInclusionFilter(project: ProjectResults): Boolean = project.warnings.exists(warningsFilter)


@main def raport(version: String) = try {
  // val debugProject = ""
  os.remove.all(LogsDir)
  val currentVersionResults = cached(projectResultsKey(version)):
    queryProjectsResultsForScalaVersion(version, failedOnly = onlyFailingProjects)
  .filter(resultsInclusionFilter)
  .map(r => r.copy(warnings = r.warnings.filter(warningsFilter)))
  // .filter(_.name == debugProject)
  printErrorStats(currentVersionResults.filter(_.isFailing), version)  
  printWarningStats(currentVersionResults, version) 

  val previousVersionResults =  cached(projectResultsKey(comparedScalaVersion)):
    queryProjectsResultsForScalaVersion(comparedScalaVersion, failedOnly = onlyFailingProjects)
  .filter(resultsInclusionFilter)
  .map(r => r.copy(warnings = r.warnings.filter(warningsFilter)))
  // .filter(_.name == debugProject)

  printErrorStats(previousVersionResults.filter(_.isFailing), comparedScalaVersion)
  printWarningStats(previousVersionResults, comparedScalaVersion)

  printErrorComparsionForVersions(currentVersionResults.filter(_.isFailing), previousVersionResults.filter(_.isFailing))
  printWarningComparsionForVersions(currentVersionResults, previousVersionResults)
} finally esClient.close()
  

opaque type TASTyVersion = String
object TASTyVersion:
  given Writer[TASTyVersion] = upickle.default.StringWriter
  given Reader[TASTyVersion] = upickle.default.StringReader
  def apply(major: Int, minor: Int, unstableRelease: Option[Int]): TASTyVersion = s"$major.$minor" + unstableRelease.map(_ => "-unstable").getOrElse("")
case class ProjectResults(name: String, scalaVersion: String,  errors: List[Error] = Nil, warnings: List[Warning] = Nil,buildURL: Option[String] = None) derives Writer, Reader:
  def isFailing = errors.nonEmpty

enum Error derives Writer, Reader:
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

enum Warning derives Writer, Reader:
  def isSameAs(that: Warning): Boolean = this.match {
      case self: CompilationWarning => that.match {
        case that: CompilationWarning =>
          // that.code == self.code && that.kind == self.kind && 
          that.sourceFile == self.sourceFile
          && {
            List(self.message, that.message)
            .map: msg =>
              msg.filter(_.isLetterOrDigit)  
            .match {
              case l :: r :: _ => 
                l == r || 
                (l.nonEmpty && r.nonEmpty && (l.contains(r) || r.contains(l))) ||
                (l.isEmpty() && r.isEmpty())
              case _ => ???
            }
          }
          && warningMaxLinesDifference.match{
            case Some(limit) =>  (that.line - self.line).abs <= limit
            case None => that.line == self.line && that.column == self.column
          }
        case Warning.DeprecatedSetting(_) => this.equals(that)
      }
      case _ => this.equals(that) 
    }
  case CompilationWarning(code: Option[Int], kind: String, sourceFile: String, line: Int, column: Int, message: String, source: Option[String], explained: Option[String])
  case DeprecatedSetting(setting: String)
object Warning:
  extension(self: CompilationWarning) 
    def sourcePosition = s"${self.sourceFile}:${self.line}:${self.column}"
extension(self: Iterable[Warning]) 
  def intersectWith(other: Iterable[Warning]) = self.filter(w => other.exists(_.isSameAs(w))).toSeq.distinct
  def diffWith(other: Iterable[Warning]) = self.filter(w => other.forall(!_.isSameAs(w))).toSeq.distinct


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

given Show[Warning] = _ match {
  case err: Warning.CompilationWarning => 
    s"""Warning${err.code.map("[" + _ + "]").getOrElse("")} ${err.kind}
       | - source file: ${err.sourceFile}:${err.line}:${err.column}
       | - source: ${err.source.map("\n" + _).getOrElse("<none>")} 
       | - messsage: ${err.message}
  """.stripMargin
  case err: Warning.DeprecatedSetting => s"Deprecated setting ${err.setting}"
}

given Show[ProjectResults] = v =>
  s"""project: ${v.name}
     |scalaVersion=${v.scalaVersion}
     |warnings: ${v.warnings.size}
     |errors: 
     |${v.errors.zipWithIndex.map((err, idx) => s"- $idx. ${err.show}}").map(_.linesIterator.map("\t\t" + _).mkString("\n")).mkString("\n")}
  """.stripMargin 

def printErrorStats(failedProjects: Iterable[ProjectResults], scalaVersion: String) = {
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

def printWarningStats(projects: Iterable[ProjectResults], scalaVersion: String) = {
  println("###############")
  println(s"Database contains reports for ${projects.size} projects using Scala $scalaVersion")
  projects.flatMap(_.warnings)
    .tap: warnings =>
      println(s"Found ${warnings.size} warnings:")
    .groupBy:
      case err: Warning.CompilationWarning => err.getClass().getSimpleName() + " - " + err.kind
      case err: Warning.DeprecatedSetting => err.setting
    .view.mapValues(_.size)
    .toSeq.sortBy(_._1)
    .foreach: (warnKind, count) =>
      println(s"  - ${warnKind}:\t$count")
  println("###############")
  println("Warnings by message category:")
  projects.toList
    .flatMap(_.warnings)
    .collect{case warn: Warning.CompilationWarning => warnings.matching(warn.message)}
    .groupBy(_.map(_.categoryName))
    .mapValues(_.size)
    .pipe: results =>
      (warnings.all.distinctBy(_.categoryName).map(Some(_)) :+ None).map: cat =>
        val name = cat.map(_.categoryName)
        val count = results.getOrElse(name, 0)
        println(s"$count\t-\t${name.getOrElse("uncatagorized")}")
  println("###############")

}

def printLine() = println("-" * 16)
def printErrorComparsionForVersions(currentResults: Set[ProjectResults], previousResults: Set[ProjectResults]) = {
  val newFailures = currentResults.map(_.name).diff(previousResults.map(_.name))
  println(s"New failures in ${newFailures.size} projects")
  newFailures.toSeq.sorted.zipWithIndex.foreach: (project, idx) => 
    val url = currentResults.find(_.name == project).flatMap(_.buildURL).getOrElse("")
    println(s"$idx.\t$project - $url")
  val allNewErrors = currentResults.flatMap(_.errors).diff(previousResults.flatMap(_.errors))
  printLine()
  println(s"New errors: ${allNewErrors.size}")

  val alreadyFailing = currentResults.map(_.name).intersect(previousResults.map(_.name))
  println(s"Projects previously failing: ${alreadyFailing.size}")

  case class ProjectComparsion(name: String,  previous: ProjectResults, current: ProjectResults, comparsion: Comparsion)
  case class ErrorsDiff(sourcePosition: Option[String], previous: Seq[Error], current: Seq[Error])
  case class Comparsion(sameErrors: Seq[Error], newErrors: Seq[Error], diffErrors: Seq[ErrorsDiff])
  val projectComparsion = alreadyFailing.map: project =>
    val current = currentResults.find(_.name == project).get
    val prev = previousResults.find(_.name == project).get
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

def printWarningComparsionForVersions(currentResults: Set[ProjectResults], previousResults: Set[ProjectResults]) = {
  val projectsWithWarnings = currentResults.filter(_.warnings.nonEmpty).map(_.name)
  println(s"Warnings found in ${projectsWithWarnings.size} projects")
  projectsWithWarnings.toSeq.sorted.zipWithIndex.foreach: (project, idx) => 
    val result = currentResults.find(_.name == project)
    val url = result.flatMap(_.buildURL).map(": " + _).getOrElse("")
    val count = result.map(_.warnings.length).get
    println(s"$idx.\t$project - $count $url")
  val allNewWarnings = currentResults.flatMap(_.warnings).diffWith(previousResults.flatMap(_.warnings))
  printLine()
  println(s"All warnings: ${allNewWarnings.size}")

  val alreadyWarning = currentResults.map(_.name).intersect(previousResults.map(_.name))
  println(s"Projects previously warning: ${alreadyWarning.size}")

  case class ProjectComparsion(name: String,  previous: ProjectResults, current: ProjectResults, comparsion: Comparsion)
  case class WarningDiff(sourcePosition: Option[String], previous: Seq[Warning], current: Seq[Warning])
  case class Comparsion(sameWarnings: Seq[Warning], newWarnings: Seq[Warning], diffWarnings: Seq[WarningDiff])
  val projectComparsion = projectsWithWarnings.map: project =>
    val current = currentResults.find(_.name == project).get
    val prev = previousResults.find(_.name == project).getOrElse(ProjectResults(name = project, scalaVersion = comparedScalaVersion))
    // println(s"previous: [${prev.warnings.size}]")
    // prev.warnings.map(_.show).foreach(println)
    // println(s"current: [${current.warnings.size}]")
    // prev.warnings.map(_.show).foreach(println)

    val newWarnings = current.warnings.diffWith(prev.warnings).filter:
        case err: Warning.CompilationWarning => !prev.warnings.exists:
          case prev: Warning.CompilationWarning  => err.sourcePosition == prev.sourcePosition 
          case _ => false
        case _ => true
      .sortBy(_.ordinal)
    def otherWarnings(warns: Seq[Warning]) = warns.filterNot(_.isInstanceOf[Warning.CompilationWarning ])
    ProjectComparsion(name = project, 
      previous = prev,
      current = current,
      Comparsion(
        sameWarnings = current.warnings.intersectWith(prev.warnings).sortBy(_.ordinal),
        newWarnings  = newWarnings,
        diffWarnings = ((current.warnings.diffWith(prev.warnings) ++ prev.warnings.diffWith(current.warnings))).collect:
            case err: Warning.CompilationWarning => err
          .groupBy(_.sourcePosition)
          .filter(_._2.size > 1)
          .toSeq
          .map: (sourcePosition, compilationWarnings) => 
            WarningDiff(sourcePosition = Some(sourcePosition),
             current = current.warnings.intersectWith(compilationWarnings),
             previous = prev.warnings.intersectWith(compilationWarnings)
            )
          .appended: 
            WarningDiff(sourcePosition = None, 
              current = otherWarnings(newWarnings),
              previous = otherWarnings(prev.warnings).diffWith(otherWarnings(newWarnings))
            ) 
          .filter(diff => diff.previous.nonEmpty && diff.current.nonEmpty)
          .sortBy(_.sourcePosition)
      ))
  val (withSameWarnings, withDiffWarnings) = projectComparsion.partition: project => 
    project.comparsion.diffWarnings.isEmpty && project.comparsion.newWarnings.isEmpty

  // println(s"Projects with the same warnings: ${withSameWarnings.size}")
  // withSameWarnings.foreach(_.current.warnings.foreach(println))
  // if listSameWarningProjects && withSameWarnings.nonEmpty then
  //   withSameWarnings.toSeq.sortBy(_.name).zipWithIndex.foreach: (p, idx) =>
  //     println(s"$idx: ${p.name}: ${p.current.warnings.size} same warnings")
  //   printLine()

  val diffWarningProjects = withDiffWarnings.filter(_.comparsion.diffWarnings.nonEmpty)
  println(s"Projects with different warnings: ${diffWarningProjects.size}")
  if listDiffWarningProjects && diffWarningProjects.nonEmpty then
    diffWarningProjects.toSeq.sortBy(_.name).zipWithIndex.foreach: (p, idx) =>
      println(s"$idx: ${p.name}: ${p.comparsion.diffWarnings.size} different warnings ${p.current.buildURL.zip(p.previous.buildURL).map("- " + _ + " vs " + _).getOrElse("")}")
    printLine()

  val newWarningProjects = withDiffWarnings.filter(_.comparsion.newWarnings.nonEmpty)
  println(s"Projects with new warnings: ${withDiffWarnings.filter(_.comparsion.newWarnings.nonEmpty).size}")
  if listNewWarningProjects && newWarningProjects.nonEmpty then
    newWarningProjects.toSeq.sortBy(_.name).zipWithIndex.foreach: (p, idx) =>
      println(s"$idx: ${p.name}: ${p.comparsion.newWarnings.size} new warnings ${p.current.buildURL.map("- " + _).getOrElse("")}")
    printLine()
  
  if showNewWarnings || showDiffWarnings then {
    withDiffWarnings.toSeq.sortBy(_.name).zipWithIndex.foreach{ (p, idx) =>    
      println(s"$idx: ${p.name}: ${p.comparsion.diffWarnings.size} diff warnings, ${p.comparsion.newWarnings.size} new warnings, ${p.comparsion.sameWarnings.size} same warnings")
      if showNewWarnings && p.comparsion.newWarnings.nonEmpty then
        println(s"New warnings: ${p.comparsion.newWarnings.size}")
        p.comparsion.newWarnings.zipWithIndex.foreach: (err, idx) =>
          println(s"* $idx. ${err.show}")
        printLine()

      if showDiffWarnings && p.comparsion.diffWarnings.nonEmpty then
        println(s"Different warnings: ${p.comparsion.diffWarnings.size}")
        p.comparsion.diffWarnings.zipWithIndex.foreach: (diff, idx) =>
          println(s"$idx. Compilation warning at ${diff.sourcePosition}")
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
        Error.CompilationError(code = Some(code.toInt), kind = kind, sourceFile = normalizePaths(sourceFile), line = line.toInt, column = column.toInt, message = "",source = None, explained = None) :: acc

      case (acc, msg @ s"[error] ${_} ${kind}: ${sourceFile}:${line}:${tail}") if sourceFile.endsWith(".scala")  =>
        val column = tail.takeWhile(_.isDigit)
        isParsingError = true
        Error.CompilationError(code = None, kind = kind, sourceFile = normalizePaths(sourceFile), line = line.toInt, column = column.toInt, message = "",source = None, explained = None) :: acc

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

      case (acc, s"[error] ($module / $scope / $task) ${NormalizedMessage(msg)}") =>
        if IgnoredExceptions.exists(msg.contains) || IgnoredTasks.contains(task) then acc
        else Error.BuildTaskFailure(s"Task failed: ${module}/$scope/$task : $msg") :: acc
      case (acc, s"[error] ($module / $task) ${NormalizedMessage(msg)}")  =>
        if IgnoredExceptions.exists(msg.contains) || IgnoredTasks.contains(task) then acc
        else Error.BuildTaskFailure(s"Task failed: ${module}/$task : $msg") :: acc
 
      case (acc @ (last :: tail), line @ s"[error] ${_}") if isParsingError => {
        last match
          case last: Error.CompilationError =>
            line match {
              case s"[error] ${sourceLine}|${source}" if sourceLine.trim().toIntOption.contains(last.line) =>
                last.copy(source = Some(normalizePaths(source))) :: tail
              case s"[error] ${_}|Explanation (enabled by `-explain`)" | s"[error] Explanation" =>
                last.copy(explained = Some("")) :: tail
              case s"[error]${_}|${NormalizedMessage(message)}" => 
                val updated = 
                  if message.trim().forall(_ == '^') then 
                    last.copy(source = last.source.map(_ + "\n" + message))
                  else if last.explained.isDefined then
                    last.copy(explained = last.explained.map(_ + message + "\n"))
                  else 
                    last.copy(message = last.message + "\n" + message) 
                updated :: tail 
              case s"[error] ${NormalizedMessage(message)}" if last.explained.isDefined => 
                val updated = last.copy(explained = last.explained.map(_ + message + "\n"))
                updated :: tail 
              case msg =>
                if showIgnoredLogLines then println(s"Ignored [${logLine}] compilation error: ${msg}")
                acc
            }
          case last: Error.CompilationCrash => line match {
            case s"[error] ${NormalizedMessage(msg)}" =>
              last.copy(stacktrace = last.stacktrace :+ msg) :: tail
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
             case s"[error] ${NormalizedMessage(msg)}" => 
              last.copy(stacktrace = last.stacktrace :+ msg) :: tail
            case _ => acc
          }
          case _: Error.MissingDependency => acc
          case _: Error.BuildTaskFailure => acc 
          case _: Error.Misconfigured => acc
        }
 
      case (acc, s"[error] ${NormalizedMessage(exception)}") 
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

def normalizePaths(msg: String): String = {
  msg
    .replaceAll("/build/repo/", "")
    .replaceAll(raw"/scala-[\w+\d+\.\-]+/", "/SCALA_VERSION/")
}

object NormalizedMessage:
  def unapply(msg: String): Option[String] = 
    Option:
      normalizePaths(msg)
    .filterNot(_.trim().isBlank())

def parseWarningLogs(logs: String): List[Warning] = {
  var logLine = 0
  var isParsingWarning = false

  logs.lines().toScala(LazyList)
    .tapEach(_ => logLine += 1)
    .foldLeft(List.empty[Warning]){
      case (acc, msg @ s"[warn] ${_} [E${code}] ${kind}: ${sourceFile}:${line}:${tail}") =>
        val column = tail.takeWhile(_.isDigit)
        isParsingWarning = true
        Warning.CompilationWarning(code = Some(code.toInt), kind = kind, sourceFile = normalizePaths(sourceFile), line = line.toInt, column = column.toInt, message = "",source = None, explained = None) :: acc
      case (acc, msg @ s"[warn] ${_} ${kind}: ${sourceFile}:${line}:${tail}") if sourceFile.endsWith(".scala") && line.toIntOption.isDefined  =>
        val column = tail.takeWhile(_.isDigit)
        isParsingWarning = true
        Warning.CompilationWarning(code = None, kind = kind, sourceFile = normalizePaths(sourceFile), line = line.toInt, column = column.toInt, message = "",source = None, explained = None) :: acc

      case (acc, s"[warn] Option ${usedOption} is deprecated${_}") =>
        Warning.DeprecatedSetting(usedOption) :: acc
 
      case (acc @ (last :: tail), line @ s"[warn] ${_}") if isParsingWarning => {
        last match
          case last: Warning.CompilationWarning =>
            line match {
              case s"[warn] ${sourceLine}|${source}" if sourceLine.trim().toIntOption.contains(last.line) =>
                last.copy(source = Some(normalizePaths(source))) :: tail
              case s"[warn] ${_}|Explanation (enabled by `-explain`)" | s"[error] Explanation" =>
                last.copy(explained = Some("")) :: tail
              case s"[warn]${_}|${NormalizedMessage(message)}" => 
                val updated = 
                  if message.trim().forall(_ == '^') then 
                    last.copy(source = last.source.map(_ + "\n" + message))
                  else if last.explained.isDefined then
                    last.copy(explained = last.explained.map(_ + message + "\n"))
                  else 
                    last.copy(message = last.message + "\n" + message) 
                updated :: tail 
              case s"[warn] ${NormalizedMessage(message)}" if last.explained.isDefined => 
                val updated = last.copy(explained = last.explained.map(_ + message + "\n"))
                updated :: tail 
              case msg =>
                if showIgnoredLogLines then println(s"Ignored [${logLine}] compilation error: ${msg}")
                acc
            }
          case _: Warning.DeprecatedSetting => acc
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
    case err: Warning.DeprecatedSetting => err.setting
  }
  .filter {
    case err: Warning.CompilationWarning => !IgnoredWarningMessages.exists(err.message.contains)
    case _ => true
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

def queryProjectsResultsForScalaVersion(scalaVersion: String, failedOnly: Boolean) = 
   println(s"Querying project results, scalaVersion=$scalaVersion, failedOnly=$failedOnly")
   esClient
    .execute:
      search(BuildSummariesIndex)
        .query:
          boolQuery().must(
            termQuery("scalaVersion", scalaVersion),
            termsQuery("status", Seq("failure") ++ Option.when(!failedOnly)("success")),
            not(
              Option.when(failedOnly):
                termsQuery("projectName", passingProjectsForScalaVersion(scalaVersion).toSeq) 
              ++ Seq(
                matchPhraseQuery("logs", "TASTy signature has wrong version"),
                matchPhraseQuery("logs", "Incompatible TASTy version"),
                matchPhraseQuery("logs", "is not a valid choice for -source")
              )
            )
          )
        .size(10 * 1000)
    .await(DefaultTimeout)
    .fold(
      err => sys.error(s"Failed to list projects from Elasticsearch: ${err.error}"),
      result => 
        println(s"Processing ${result.hits.hits.size} results.")
        result.hits.hits.filter: hit =>
          !failedOnly
          || hit.sourceField("summary").asInstanceOf[List[Map[String, Map[String, String]]]]
            .exists: summary =>
              summary.get("compile").flatMap(_.get("status")).contains("failed") 
              || summary.get("test-compile").flatMap(_.get("status")).contains("failed")
        .map: hit =>
          ProjectResults(
            name = hit.sourceField("projectName").asInstanceOf[String],
            errors = parseErrorLogs(hit.sourceField("logs").asInstanceOf[String]),
            warnings = 
              if parseWarnings then parseWarningLogs(hit.sourceField("logs").asInstanceOf[String])
              else Nil,
            scalaVersion = hit.sourceField("scalaVersion").asInstanceOf[String],
            buildURL = hit.sourceFieldOpt("buildURL").map(_.toString().trim()).filterNot(_.isEmpty)
          ).tap: p => 
            if failedOnly && (p.errors.isEmpty || p.warnings.isEmpty) then 
              println(s"No errors or warnings parsed in ${p.name}")
              val logDir = LogsDir / os.RelPath(p.name)
              os.makeDir.all(logDir)
              os.write.over(logDir / s"${p.scalaVersion}.log", hit.sourceField("logs").asInstanceOf[String])
        .toSet
    )


trait Show[T]:
  extension (v: T) def show: String 


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
