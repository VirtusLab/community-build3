#!/usr/bin/env -S scala-cli shebang
//> using scala "3"
//> using lib "com.sksamuel.elastic4s:elastic4s-client-esjava_2.13:8.5.2"
//> using lib "org.slf4j:slf4j-simple:2.0.6"

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
import scala.io.AnsiColor.*
import org.elasticsearch.client.RestClient
import org.apache.http.HttpHost

given ExecutionContext = ExecutionContext.global

val BuildSummariesIndex = "project-build-summary"
val DefaultTimeout = 5.minutes
val ElasticsearchHost = "scala3.westeurope.cloudapp.azure.com"
val ElasticsearchUrl = s"https://$ElasticsearchHost/data/"
// ./scripts/show-elastic-credentials.sh
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
lazy val PreviousScalaReleases = (StableScalaVersions ++ NightlyReleases).sorted.tapEach(println)

// Report all community build filures for given Scala version
@main def raportForScalaVersion(scalaVersion: String, opts: String*) =
  val checkBuildId = opts.collectFirst {
    case opt if opt.contains("-buildId=") => opt.dropWhile(_ != '=').tail
  }
  val compareWithScalaVersion = opts.collectFirst {
    case opt if opt.contains("-compareWith=") => opt.dropWhile(_ != '=').tail
  }
  val compareWithBuildId = opts.collectFirst {
    case opt if opt.contains("-compareWithBuildId=") =>
      opt.dropWhile(_ != '=').tail
  }

  printLine()
  println(
    s"Reporting failed community build projects using Scala $scalaVersion"
  )
  val failedProjects = listFailedProjects(scalaVersion, checkBuildId)
  printLine()
  val reportedProjects = compareWithScalaVersion
    .foldRight(failedProjects) { case (comparedVersion, failedNow) =>
      println(s"Excluding projects failing already in $comparedVersion")
      val ignoredProjects =
        listFailedProjects(
          comparedVersion,
          buildId = compareWithBuildId,
          logFailed = false
        )
          .map(_.project)
          .toSet
      println(s"Excluding ${ignoredProjects.size} project failing in ${comparedVersion}${compareWithBuildId.fold("")(" in buildId=" + _)}:")
      ignoredProjects
        .map(_.orgRepoName)
        .groupBy(_.head)
        .toList
        .sortBy(_._1)
        .map(_._2.toList.sorted.mkString(" - ",", ", ""))
        .foreach(println)

      failedNow.filter(p => !ignoredProjects.contains(p.project))
    }
  if reportedProjects.nonEmpty then
    reportCompilerRegressions(reportedProjects, scalaVersion)(
      Reporter.Default(scalaVersion)
    )
  printLine()
  esClient.close()

case class Project(searchName: String) extends AnyVal {
  def orgRepoName = searchName.replace("_", "/")
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
    scalaVersion: String,
    buildId: Option[String],
    logFailed: Boolean = true
): Seq[FailedProject] =
  println(
    s"Listing failed projects in compiled with Scala ${scalaVersion}" +
      buildId.fold("")(id => s"with buildId=$id")
  )
  val Limit = 2000
  val projectVersionsStatusAggregation =
    termsAgg("versions", "version")
      .order(TermsOrder("buildTimestamp", asc = false))
      .subaggs(
        maxAgg("buildTimestamp", "timestamp"),
        termsAgg("status", "status")
      )
      .size(10) // last versions

  def process(resp: SearchResponse): Seq[FailedProject] = {
    val projectVersions = resp.aggs
      .terms("failedProjects")
      .buckets
      .map { bucket =>
        val name = bucket.key
        val lastVersion = bucket.terms("versions").buckets.head.key
        name -> lastVersion
      }
      .toMap

    def hasNewerPassingVersion(project: Project, failedVersion: String) =
      esClient
        .execute {
          search(BuildSummariesIndex)
            .query(
              boolQuery().must(
                termQuery("projectName", project.searchName),
                termQuery("status", "success"),
                termQuery("scalaVersion", scalaVersion)
              )
            )
            .sourceInclude("version")
            .sortBy(fieldSort("timestamp").desc())
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

    resp.hits.hits
      .map(_.sourceAsMap)
      .distinctBy(_("projectName"))
      .flatMap { fields =>
        val project = Project(fields("projectName").asInstanceOf[String])
        val summary = fields("summary").asInstanceOf[List[SourceFields]]
        val buildURL = fields("buildURL").asInstanceOf[String]
        val lastFailedVersion = projectVersions(project.searchName)

        import scala.io.AnsiColor.{RED, YELLOW, MAGENTA, RESET, BOLD}
        def logProject(label: String)(color: String) = if logFailed then
          println(
            s"$color${label.padTo(8, " ").mkString}$RESET failure in $BOLD${project.orgRepoName} @ ${projectVersions(
                project.searchName
              )}$RESET - $buildURL"
          )
        val compilerFailure = summary.compilerFailure
        if hasNewerPassingVersion(project, lastFailedVersion) then
          None // ignore failure
        else
          if summary.compilerFailure then logProject("COMPILER")(RED)
          if summary.testsFailure then logProject("TEST")(YELLOW)
          if summary.docFailure then logProject("DOC")(MAGENTA)
          if summary.publishFailure then logProject("PUBLISH")(MAGENTA)
          Option.when(compilerFailure) {
            FailedProject(
              project,
              version = lastFailedVersion,
              buildURL = buildURL
            )
          }
      }
  }

  esClient
    .execute {
      search(BuildSummariesIndex)
        .query(
          boolQuery()
            .must(
              Seq(
                termQuery("scalaVersion", scalaVersion),
                termQuery("status", "failure")
              ) ++ buildId.map(termQuery("buildId", _))
            )
        )
        .size(Limit)
        .sourceInclude("projectName", "summary", "buildURL")
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
              termQuery("projectName", project.project.searchName)
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
    }
    .map(
      _.fold[Seq[ProjectHistoryEntry]](
        reportFailedQuery(
          s"Project build history ${project.project.orgRepoName}"
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
      ) =
        println(s"| $project | $version | $buildURL | $notes |")
      val allRegressions = sameVersionRegressions ++ diffVersionRegressions
      printLine()
      println(
        s"Projects with last successful builds using Scala <b>$BOLD$scalaVersion$RESET</b> [${allRegressions.size}]:"
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
          s"[Open CB logs]($url)"
        }
        showRow(p.project.orgRepoName, version, buildUrl)
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
          .sortBy(_.project.searchName)
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
        .sortBy(_.project.orgRepoName),
      Nil
    )

end reportCompilerRegressions

def printLine() = println("- " * 40)
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
