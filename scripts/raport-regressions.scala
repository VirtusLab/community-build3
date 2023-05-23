//> using scala "3"
//> using lib "com.sksamuel.elastic4s:elastic4s-client-esjava_2.13:8.6.0"
//> using lib "org.slf4j:slf4j-simple:2.0.7"

import com.sksamuel.elastic4s
import elastic4s.*
import elastic4s.http.JavaClient
import elastic4s.ElasticDsl.*
import elastic4s.requests.searches.aggs.TermsOrder
import elastic4s.requests.searches.*

import org.elasticsearch.client.RestClientBuilder.HttpClientConfigCallback
import org.apache.http.impl.nio.client.*
import org.apache.http.impl.client.BasicCredentialsProvider
import org.apache.http.auth.*

import scala.io.Source
import scala.concurrent.*
import scala.concurrent.duration.*
import org.elasticsearch.client.RestClient
import org.apache.http.HttpHost

import Printer.{println, *}

given ExecutionContext = ExecutionContext.global

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

lazy val NightlyReleases = {
  val re = raw"(?<=title=$")(.+-bin-\d{8}-\w{7}-NIGHTLY)(?=/$")".r
  val html = Source.fromURL(
    "https://repo1.maven.org/maven2/org/scala-lang/scala3-compiler_3/"
  )
  re.findAllIn(html.mkString).toVector
}

lazy val StableScalaVersions = {
  val re = raw"(?<=title=$")(\d+\.\d+\.\d+(-RC\d+)?)(?=/$")".r
  val html = Source.fromURL(
    "https://repo1.maven.org/maven2/org/scala-lang/scala3-compiler_3/"
  )
  re.findAllIn(html.mkString).toVector
}
lazy val PreviousScalaReleases = (StableScalaVersions ++ NightlyReleases).sorted

// Report all community build filures for given Scala version
@main def raportForScalaVersion(opts: String*) = try {
  val scalaVersion = opts.toList.filterNot(_.startsWith("-")) match {
    case Nil            => None
    case version :: Nil => Some(version).filter(_.nonEmpty)
    case multiple       => sys.error("Expected a single argument <scalaVersion>")
  }
  scalaVersion.foreach(v => log("Checking Scala: " + v))

  val checkBuildId = opts
    .collectFirst {
      case opt if opt.contains("-buildId=") => opt.dropWhile(_ != '=').tail
    }
    .filter(_.nonEmpty)
  checkBuildId.foreach(v => log("Checking buildId: " + v))

  require(
    checkBuildId.orElse(scalaVersion).isDefined,
    "scalaVersion argument or --buildId argument required"
  )

  val compareWithBuildId = opts
    .collectFirst {
      case opt if opt.contains("-compareWithBuildId=") =>
        opt.dropWhile(_ != '=').tail
    }
    .filter(_.nonEmpty)
  compareWithBuildId.foreach(v => log("Comparing with buildId: " + v))

  val compareWithScalaVersion = opts
    .collectFirst {
      case opt if opt.contains("-compareWith=") => opt.dropWhile(_ != '=').tail
    }
    .filter(_.nonEmpty)
  compareWithScalaVersion.foreach(v => log("Comparing Wtih Scala version: " + v))

  printLine()
  println(
    s"Reporting failed community build projects using Scala $scalaVersion:$LINE_BREAK\n"
  )
  val failedProjects = listFailedProjects(scalaVersion, checkBuildId)
  printLine()

  val reportedProjects =
    if compareWithScalaVersion.orElse(compareWithBuildId).isEmpty
    then failedProjects
    else {
      def id = s"scalaVersion=$compareWithScalaVersion, buildId=${compareWithBuildId}"
      println(s"Excluding projects failing already in $id:$LINE_BREAK\n")
      val ignoredProjects =
        listFailedProjects(
          compareWithScalaVersion,
          buildId = compareWithBuildId,
          logFailed = false
        )
          .map(_.project)
          .toSet
      println(s"Excluding ${ignoredProjects.size} project failing in $id:$LINE_BREAK\n")
      ignoredProjects
        .map(_.name)
        .groupBy(_.head)
        .toList
        .sortBy(_._1)
        .map(_._2.toList.sorted.mkString(" - ", ", ", ""))
        .foreach(println)

      failedProjects.filter(p => !ignoredProjects.contains(p.project))
    }
  if reportedProjects.nonEmpty then
    val comparedVersion = scalaVersion.getOrElse("<comparing builds ids>")
    reportCompilerRegressions(reportedProjects, comparedVersion)(
      Reporter.Default(comparedVersion)
    )
  printLine()
} finally esClient.close()

case class Project(name: String) extends AnyVal {
  def searchName = name
  def legacySearchName = name.replace("/", "_")
}
object Project {
  def apply(rawName: String): Project = new Project(rawName match
    case s"${org}/$repo"   => rawName
    case s"${org}_${repo}" => s"$org/$repo"
    case invalid           => sys.error(s"Invalid project name format: ${invalid}")
  )
}
case class FailedProject(project: Project, version: String, buildURL: String)
type SourceFields = Map[String, AnyRef]
// Unsafe API to check project summary results in all the modules
extension (summary: List[SourceFields])
  def compilerFailure: Boolean = existsModuleThat {
    hasFailedIn("compile") || hasFailedIn("test-compile")
  }
  def testsFailure: Boolean = existsModuleThat(hasFailedIn("test"))
  def publishFailure: Boolean = existsModuleThat(hasFailedIn("publish"))
  def docFailure: Boolean = existsModuleThat(hasFailedIn("doc"))

  private def hasFailedIn(task: String)(using module: SourceFields) = {
    val taskResults = module(task).asInstanceOf[SourceFields]
    taskResults("status") == "failed"
  }
  private def existsModuleThat(pred: SourceFields ?=> Boolean) =
    summary.exists(pred(using _))
end extension

def listFailedProjects(
    scalaVersion: Option[String],
    buildId: Option[String],
    logFailed: Boolean = true
): Seq[FailedProject] =
  log(
    s"Listing failed projects in compiled with Scala=${scalaVersion}, buildId=${buildId}"
  )
  val Limit = 2000
  val projectVersionsStatusAggregation =
    termsAgg("versions", "version")
      .order(TermsOrder("buildTimestamp", asc = false))
      .subaggs(
        maxAgg("buildTimestamp", "timestamp"),
        termsAgg("status", "status")
      )
      .size(100) // last versions

  def process(resp: SearchResponse): Seq[FailedProject] = {
    val projectVersions = resp.aggs
      .terms("failedProjects")
      .buckets
      .map { bucket =>
        val name = bucket.key
        val lastVersion = bucket.terms("versions").buckets.head.key
        Project(name) -> lastVersion
      }
      .toMap

    def hasNewerPassingVersion(scalaVersion: String, project: Project, failedVersion: String) =
      esClient
        .execute {
          search(BuildSummariesIndex)
            .query(
              boolQuery().must(
                // Either legact org_repo format or regular org/repo
                termsQuery(
                  "projectName",
                  project.searchName,
                  project.legacySearchName
                ),
                termQuery("status", "success"),
                termQuery("scalaVersion", scalaVersion)
              )
            )
            .sourceInclude("version")
            .sortBy(fieldSort("timestamp").desc())
            .size(100)
        }
        .map(_.map(_.hits.hits.exists { result =>
          isVersionNewerOrEqualThen(
            version = result.sourceField("version").asInstanceOf[String],
            reference = failedVersion
          )
        }))
        .await(DefaultTimeout)
        .result
    end hasNewerPassingVersion

    case class TimedFailure(
        project: FailedProject,
        timestamp: String,
        logProject: () => Unit
    )
    resp.hits.hits
      .map(_.sourceAsMap)
      .distinctBy(_("projectName"))
      .flatMap { fields =>
        val project = Project(fields("projectName").asInstanceOf[String])
        val summary = fields("summary").asInstanceOf[List[SourceFields]]
        val buildURL = fields("buildURL").asInstanceOf[String]
        val timestamp = fields("timestamp").asInstanceOf[String]
        val lastFailedVersion = projectVersions(project)

        def logProject(label: String)(color: String) = if logFailed then
          val name = label.padTo(8, " ").mkString
          val ver = projectVersions(project)
          println(
            s"$color$name${RESET} failure in ${BOLD}${Printer
              .projectUrlString(project.name, ver, buildURL)}$LINE_BREAK"
          )
        val compilerFailure = summary.compilerFailure
        if scalaVersion.exists(hasNewerPassingVersion(_, project, lastFailedVersion)) then
          None // ignore failure
        else
          def lazyLogProject() =
            if summary.compilerFailure then logProject("COMPILER")(RED)
            if summary.testsFailure then logProject("TEST")(YELLOW)
            if summary.docFailure then logProject("DOC")(MAGENTA)
            if summary.publishFailure then logProject("PUBLISH")(MAGENTA)
          end lazyLogProject
          Option.when(compilerFailure) {
            TimedFailure(
              project = FailedProject(
                project,
                version = lastFailedVersion,
                buildURL = buildURL
              ),
              timestamp = timestamp,
              logProject = lazyLogProject _
            )
          }
      }
      .groupBy(_.project.project)
      .toSeq
      .sortBy { case (project, _) => project.name }
      .map { case (_, failures) =>
        val last = failures.maxBy(_.timestamp)
        last.logProject()
        last.project
      }
  }

  esClient
    .execute {
      search(BuildSummariesIndex)
        .query(
          boolQuery()
            .must(
              Seq(termQuery("status", "failure"))
                ++ Seq(
                  buildId.map(termQuery("buildId", _)),
                  scalaVersion.map(termQuery("scalaVersion", _))
                ).flatten
            )
        )
        .size(Limit)
        .sourceInclude("projectName", "summary", "buildURL", "timestamp")
        .sortBy(fieldSort("projectName"), fieldSort("timestamp").desc())
        .aggs(
          termsAgg("failedProjects", "projectName")
            .size(Limit)
            .subaggs(projectVersionsStatusAggregation)
        )
    }
    .await(DefaultTimeout)
    .fold(
      reportFailedQuery("GetFailedQueries").andThen(_ => Nil),
      process(_)
    )

end listFailedProjects

case class ProjectHistoryEntry(
    project: Project,
    scalaVersion: String,
    version: String,
    isCurrentVersion: Boolean,
    compilerFailure: Boolean
)
def projectHistory(project: FailedProject) =
  esClient
    .execute {
      search(BuildSummariesIndex)
        .query {
          boolQuery()
            .must(
              termsQuery("scalaVersion", PreviousScalaReleases),
              termsQuery(
                "projectName",
                project.project.searchName,
                project.project.legacySearchName
              )
            )
            .should(
              termQuery("version", project.version)
            )
        }
        .sortBy(
          fieldSort("scalaVersion").desc(),
          fieldSort("timestamp").desc()
        )
        .sourceInclude("scalaVersion", "version", "summary")
        .size(100)
    }
    .map(
      _.fold[Seq[ProjectHistoryEntry]](
        reportFailedQuery(
          s"Project build history ${project.project.name}"
        )
          .andThen(_ => Nil),
        _.hits.hits
          .map(_.sourceAsMap)
          .distinctBy(fields => (fields("scalaVersion"), fields("version")))
          .map { fields =>
            val version = fields("version").asInstanceOf[String]
            val isCurrentVersion = project.version == version
            ProjectHistoryEntry(
              project.project,
              scalaVersion = fields("scalaVersion").asInstanceOf[String],
              version = fields("version").asInstanceOf[String],
              isCurrentVersion = isCurrentVersion,
              compilerFailure = fields("summary")
                .asInstanceOf[List[SourceFields]]
                .compilerFailure
            )
          }
      )
    )
    .await(DefaultTimeout)
end projectHistory

trait Reporter {
  def prelude: String = ""
  def report(
      scalaVersion: String,
      failedProjects: Map[Project, FailedProject],
      sameVersionRegressions: Seq[ProjectHistoryEntry],
      diffVersionRegressions: Seq[ProjectHistoryEntry]
  ): Unit
}
object Reporter:
  class Default(testedScalaVersion: String) extends Reporter:
    override def prelude: String = ""

    override def report(
        scalaVersion: String,
        failedProjects: Map[Project, FailedProject],
        sameVersionRegressions: Seq[ProjectHistoryEntry],
        diffVersionRegressions: Seq[ProjectHistoryEntry]
    ): Unit = {
      def showRow(
          project: String,
          version: String,
          buildURL: String,
          notes: String = ""
      ) = println(s"| $project | $version | $buildURL | $notes |")
      val allRegressions = sameVersionRegressions ++ diffVersionRegressions
      printLine()
      println(
        s"Projects with last successful builds using Scala $BOLD$scalaVersion$RESET [${allRegressions.size}]:${LINE_BREAK}\n"
      )

      showRow("Project", "Version", "Build URL", "Notes")
      showRow("-------", "-------", "---------", "-----")
      for p <- allRegressions do
        val version = failedProjects
          .get(p.project)
          .collect {
            case failed if p.version != failed.version =>
              s"${p.version} -> ${failed.version}"
          }
          .getOrElse(p.version)
        val buildUrl = {
          val url = failedProjects(p.project).buildURL
          if url.isEmpty then "" else s"[Open CB logs]($url)"
        }
        showRow(p.project.name, version, buildUrl)
    }
  end Default

private def reportCompilerRegressions(
    projects: Seq[FailedProject],
    scalaVersion: String
)(reporter: Reporter): Unit =
  val failedProjectHistory =
    projects
      .zip(projects.map(this.projectHistory))
      .toMap

  val failedProjects = projects.map(v => v.project -> v).toMap
  val projectHistory = failedProjectHistory.map { (key, value) =>
    key.project -> value
  }
  val allHistory = projectHistory.values.flatten.toSeq

  if reporter.prelude.nonEmpty then
    printLine()
    println(reporter.prelude)
  val alwaysFailing =
    PreviousScalaReleases.reverse
      .dropWhile(isVersionNewerOrEqualThen(_, scalaVersion))
      .foldLeft(failedProjects.keySet) { case (prev, scalaVersion) =>
        def regressionsSinceLastVersion(exactVersion: Boolean) = allHistory
          .filter(v =>
            v.scalaVersion == scalaVersion &&
              v.isCurrentVersion == exactVersion &&
              !v.compilerFailure && // was successful in checked version
              prev.contains(v.project) // failed in newer version
          )
          .sortBy(_.project.name)
        val sameVersionRegressions =
          regressionsSinceLastVersion(exactVersion = true)
        val diffVersionRegressions =
          regressionsSinceLastVersion(exactVersion = false)
            .diff(sameVersionRegressions)
        val allRegressions = sameVersionRegressions ++ diffVersionRegressions

        if allRegressions.isEmpty then prev
        else
          reporter.report(
            scalaVersion,
            failedProjects,
            sameVersionRegressions,
            diffVersionRegressions
          )
          prev.diff(allRegressions.map(_.project).toSet)
      }
  if alwaysFailing.nonEmpty then
    reporter.report(
      "with no successful builds data",
      failedProjects,
      alwaysFailing
        .map { project =>
          projectHistory(project)
            .find(_.scalaVersion == scalaVersion)
            .getOrElse(
              ProjectHistoryEntry(
                project = project,
                scalaVersion = scalaVersion,
                version = "",
                isCurrentVersion = true,
                compilerFailure = true
              )
            )
        }
        .toSeq
        .sortBy(_.project.name),
      Nil
    )

end reportCompilerRegressions

def reportFailedQuery(msg: String)(err: RequestFailure) =
  System.err.println(s"Query failure - $msg\n${err.error}")
def isVersionNewerThen(version: String, reference: String) =
  val List(ref, ver) = List(reference, version)
    .map(_.split("[.-]").flatMap(_.toIntOption))
  if reference.startsWith(version) && ref.length > ver.length then true
  else if version.startsWith(reference) && ver.length > ref.length then false
  else
    val maxLength = ref.length max ver.length
    def loop(ref: List[Int], ver: List[Int]): Boolean =
      (ref, ver) match {
        case (r :: nextRef, v :: nextVer) =>
          if v > r then true
          else if v < r then false
          else loop(nextRef, nextVer)
        case _ => false
      }
    end loop
    loop(ref.padTo(maxLength, 0).toList, ver.padTo(maxLength, 0).toList)
end isVersionNewerThen
def isVersionNewerOrEqualThen(version: String, reference: String) =
  version == reference || isVersionNewerThen(version, reference)
