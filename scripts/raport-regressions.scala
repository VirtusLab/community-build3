//> using scala "3.1.3"
//> using lib "com.sksamuel.elastic4s:elastic4s-client-esjava_2.13:8.2.1"
//> using lib "org.slf4j:slf4j-simple:1.6.4"

import com.sksamuel.elastic4s
import elastic4s.*
import elastic4s.http.JavaClient
import elastic4s.ElasticDsl.*
import elastic4s.requests.searches.aggs.TermsOrder

import org.elasticsearch.client.RestClientBuilder.HttpClientConfigCallback
import org.apache.http.impl.nio.client.*
import org.apache.http.impl.client.BasicCredentialsProvider
import org.apache.http.auth.*

import scala.concurrent.*
import scala.concurrent.duration.*
import scala.io.AnsiColor.*

given ExecutionContext = ExecutionContext.global

val BuildSummariesIndex = "project-build-summary"
val DefaultTimeout = 5.minutes
val ElasticsearchUrl = "https://localhost:9200"
// ./scripts/show-elastic-credentials.sh
val ElasticsearchCredentials = new UsernamePasswordCredentials(
  sys.env.getOrElse("ES_USERNAME", "elastic"),
  sys.env.getOrElse("ES_PASSWORD", "changeme")
)
val StableScalaVersions = {
  def versions(version: String)(rcVersions: Int) =
    1.to(rcVersions).map(v => s"$version-RC$v") ++ Seq(version)
  Seq(
    versions("3.0.0")(rcVersions = 0),
    versions("3.0.1")(rcVersions = 2),
    versions("3.0.2")(rcVersions = 2),
    versions("3.1.0")(rcVersions = 3),
    versions("3.1.1")(rcVersions = 2),
    versions("3.1.2")(rcVersions = 3),
    versions("3.1.3")(rcVersions = 5),
    versions("3.2.0")(rcVersions = 2)
  ).flatten
}

// Report all community build filures for given Scala version
@main def raportForScalaVersion(scalaVersion: String, opts: String*) =
  val createIssueTrackerTable = opts.exists(_.contains("-issueTracker"))
  printLine()
  println(s"Reporting failed community build projects using Scala $scalaVersion")
  val failedProjects = listFailedProjects(scalaVersion)
  if failedProjects.nonEmpty then
    reportCompilerRegressions(failedProjects, scalaVersion)(
      if createIssueTrackerTable then Reporter.IssueTracker(scalaVersion)
      else Reporter.Default
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
  private def existsModuleThat(pred: SourceFields ?=> Boolean) = summary.exists(pred(using _))
end extension

def listFailedProjects(scalaVersion: String): Seq[FailedProject] =
  val Limit = 1000
  val projectVersionsStatusAggregation =
    termsAgg("versions", "version")
      .order(TermsOrder("buildTimestamp", asc = false))
      .subaggs(
        maxAgg("buildTimestamp", "timestamp"),
        termsAgg("status", "status")
      )
      .size(5) // last 5 versions

  esClient
    .execute {
      search(BuildSummariesIndex)
        .query(
          boolQuery()
            .must(
              termQuery("scalaVersion", scalaVersion),
              termQuery("status", "failure")
            )
        )
        .size(Limit)
        .sortBy(fieldSort("projectName.keyword"), fieldSort("timestamp").desc())
        .aggs(
          globalAggregation("global")
            .subaggs(
              filterAgg("allResults", termQuery("scalaVersion", scalaVersion))
                .subaggs(
                  termsAgg("projects", "projectName.keyword")
                    .subaggs(projectVersionsStatusAggregation)
                    .size(Limit)
                )
            ),
          termsAgg("failedProjects", "projectName.keyword")
            .size(Limit)
            .subaggs(projectVersionsStatusAggregation)
        )
    }
    .await(DefaultTimeout)
    .fold(
      reportFailedQuery("GetFailedQueries").andThen(_ => Nil),
      resp => {
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
          resp.aggs
            .global("global")
            .filter("allResults")
            .terms("projects")
            .bucket(project.searchName)
            .terms("versions")
            .buckets
            .filter(_.terms("status").buckets.exists(_.key == "success"))
            .exists { bucket =>
              isVersionNewerOrEqualThen(version = bucket.key, reference = failedVersion)
            }

        resp.hits.hits
          .map(_.sourceAsMap)
          .distinctBy(_("projectName"))
          .flatMap { fields =>
            val project = Project(fields("projectName").asInstanceOf[String])
            val summary = fields("summary").asInstanceOf[List[SourceFields]]
            val buildURL = fields("buildURL").asInstanceOf[String]
            val lastFailedVersion = projectVersions(project.searchName)

            import scala.io.AnsiColor.{RED, YELLOW, MAGENTA, RESET, BOLD}
            def logProject(label: String)(color: String) =
              println(
                s"$color${label.padTo(8, " ").mkString}$RESET failure in $BOLD${project.orgRepoName} @ ${projectVersions(
                  project.searchName
                )}$RESET - ${fields("buildURL")}"
              )
            val compilerFailure = summary.compilerFailure
            if hasNewerPassingVersion(project, lastFailedVersion) then None // ignore failure
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
              termsQuery("scalaVersion", StableScalaVersions),
              termQuery("projectName.keyword", project.project.searchName)
            )
            .should(
              termQuery("version", project.version)
            )
        }
        .sortBy(
          fieldSort("scalaVersion").desc(),
          fieldSort("timestamp").desc()
        )
    }
    .await(DefaultTimeout)
    .fold[Seq[ProjectHistoryEntry]](
      reportFailedQuery(s"Project build history ${project.project.orgRepoName}")
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
            compilerFailure = fields("summary").asInstanceOf[List[SourceFields]].compilerFailure
          )
        }
    )
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
  object Default extends Reporter:
    override def report(
        scalaVersion: String,
        failedProjects: Map[Project, FailedProject],
        sameVersionRegressions: Seq[ProjectHistoryEntry],
        diffVersionRegressions: Seq[ProjectHistoryEntry]
    ): Unit = {
      val allRegressions = sameVersionRegressions ++ diffVersionRegressions
      def showFailed(failed: Seq[ProjectHistoryEntry]) = failed
        .map(_.project.orgRepoName)
        .map(v => s"${BOLD}$v${RESET}")
        .mkString(", ")
      def showDiffVersions() = diffVersionRegressions.foreach(v =>
        println(
          s" * $BOLD${v.project.orgRepoName}$RESET - ${v.version} -> ${failedProjects(v.project).version} "
        )
      )

      val same = sameVersionRegressions
      val diff = diffVersionRegressions
      printLine()
      println(
        scalaVersion match {
          case Some(scalaVersion) =>
            s"Projects with last successful builds using Scala $BOLD$scalaVersion$RESET [${allRegressions.size}]:"
          case None =>
            s"Projects without successful builds data [${allRegressions.size}]:"
        }
      )
      println(
        s"""Same versions[${same.size}]:    ${showFailed(same)}
           |Changed versions[${diff.size}]: ${showFailed(diff)}""".stripMargin
      )
      if diffVersionRegressions.nonEmpty then showDiffVersions()
    }

  class IssueTracker(testedScalaVersion: String) extends Reporter:
    override def prelude: String = s"""
    |Each table contains a list of projects that failed to compile with Scala ${testedScalaVersion}, but was successfuly built with the given previous version.
    |A summary is based only on final and released candidate versions of Scala 3.
    |Information about the last Scala version used for the last successful build might not always be correct, due to lack of data (lack of build for that project with given Scala version)
    |
    |Open community build might have applied -source:X-migration flag if it is detected it could possibly fix the build.
    |Summary only contains projects that failed when compiling source or test files of at least 1 sub-project. 
    | - `Version` - version of project being built, single version if both current and last successful build version of project are equal, otherwise 'LastSuccessfulProjectVersion -> CurrentProjectVersion>'
    | - `Build URL` - link to the Open Community Build, containing logs and details of the failed project
    | - `Reproducer issue` - link to the reproducer issue to be filled in
    | 
    |All tested projects: _
    |Open Community build run: [Build #<BUILD_ID> - <BUILD_NAME>](<BUILD_URL>)
    |
    |Notes for issue reproducers:
    |To reproduce builds locally you can use:
    |```
    |scala-cli run https://raw.githubusercontent.com/VirtusLab/community-build3/master/cli/scb-cli.scala -- reproduce --locally BUILD_ID
    |```
    |BUILD ID can be found in the BUILD_URL columns (eg. `Open CB #BUILD_ID`)
    |Helpful options for reproducer scripts:
    | - `--scalaVersion VERSION` - run build with the selected version of Scala (to check if the problem existed in given release)
    | - `--withUpstream` - build also all upstream dependencies of failing project
    | - `--locally` - checkout and build the project locally, without this flag it would try to start a minikube cluster to make the reproduction environment exactly the same as in the Open Community Build run (eg. to compile with the same version of the JDK)
    |
    |""".stripMargin

    override def report(
        scalaVersion: Option[String],
        failedProjects: Map[Project, FailedProject],
        sameVersionRegressions: Seq[ProjectHistoryEntry],
        diffVersionRegressions: Seq[ProjectHistoryEntry]
    ): Unit = {
      def showRow(project: String, version: String, buildURL: String, issueURL: String = "") =
        println(s"| $project | $version | $issueURL | $buildURL |")
      val allRegressions = sameVersionRegressions ++ diffVersionRegressions
      printLine()
      println(
        scalaVersion match {
          case Some(scalaVersion) =>
            s"Projects with last successful builds using Scala <b>$BOLD$scalaVersion$RESET</b> [${allRegressions.size}]:"
          case None =>
            s"Projects without successful builds data [${allRegressions.size}]:"
        }
      )
      showRow("Project", "Version", "Build URL", "Reproducer issue")
      showRow("-------", "-------", "---------", "----------------")
      for p <- allRegressions do
        val version = failedProjects
          .get(p.project)
          .collect {
            case failed if p.version != failed.version => s"${p.version} -> ${failed.version}"
          }
          .getOrElse(p.version)
        val buildUrl = {
          val url = failedProjects(p.project).buildURL
          val buildId = url.split("/").reverse.dropWhile(_.isEmpty).head
          s"[Open CB #$buildId]($url)"
        }
        showRow(p.project.orgRepoName, version, buildUrl)
    }
  end IssueTracker

private def reportCompilerRegressions(
    projects: Seq[FailedProject],
    scalaVersion: String
)(reporter: Reporter): Unit =
  val failedProjectHistory = Future
    .traverse(projects) { fp =>
      Future { (fp, this.projectHistory(fp)) }
    }
    .await(DefaultTimeout)
    .toMap

  val failedProjects = projects.map(v => v.project -> v).toMap
  val projectHistory = failedProjectHistory.map { (key, value) => key.project -> value }
  val allHistory = projectHistory.values.flatten.toSeq

  printLine()
  println(reporter.prelude)
  val alwaysFailing =
    StableScalaVersions.reverse
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
        val sameVersionRegressions = regressionsSinceLastVersion(exactVersion = true)
        val diffVersionRegressions = regressionsSinceLastVersion(exactVersion = false)
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
        .map(projectHistory(_).find(_.scalaVersion == scalaVersion).get)
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
    .map(_.split("[.-]").map(_.filter(_.isDigit).toInt))
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

lazy val esClient =
  val clientConfig = new HttpClientConfigCallback {
    override def customizeHttpClient(
        httpClientBuilder: HttpAsyncClientBuilder
    ): HttpAsyncClientBuilder = {
      val creds = new BasicCredentialsProvider()
      creds.setCredentials(AuthScope.ANY, ElasticsearchCredentials)
      httpClientBuilder
        .setDefaultCredentialsProvider(creds)
        // Custom SSL context that would not require ES certificate
        .setSSLContext(unsafe.UnsafeSSLContext)
        .setHostnameVerifier(unsafe.VerifiesAllHostNames)
    }
  }

  ElasticClient(
    JavaClient(
      ElasticProperties(ElasticsearchUrl),
      clientConfig
    )
  )
end esClient

// Used to connect in localhost port-forwarding to K8s cluster without certifacates
object unsafe:
  import javax.net.ssl.{SSLSocket, SSLSession}
  import java.security.cert.X509Certificate
  import org.apache.http.conn.ssl.X509HostnameVerifier
  object VerifiesAllHostNames extends X509HostnameVerifier {
    override def verify(x: String, y: SSLSession): Boolean = true
    override def verify(x: String, y: SSLSocket): Unit = ()
    override def verify(x: String, y: X509Certificate): Unit = ()
    override def verify(x: String, y: Array[String], x$2: Array[String]): Unit = ()
  }

  lazy val UnsafeSSLContext = {
    object TrustAll extends javax.net.ssl.X509TrustManager {
      override def getAcceptedIssuers(): Array[X509Certificate] = Array()
      override def checkClientTrusted(
          x509Certificates: Array[X509Certificate],
          s: String
      ) = ()
      override def checkServerTrusted(
          x509Certificates: Array[X509Certificate],
          s: String
      ) = ()
    }

    val instance = javax.net.ssl.SSLContext.getInstance("SSL")
    instance.init(null, Array(TrustAll), new java.security.SecureRandom())
    instance
  }
end unsafe
