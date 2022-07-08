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
import scala.io.AnsiColor.*

given ExecutionContext = ExecutionContext.global

val BuildSummariesIndex = "project-build-summary"
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
@main def report(scalaVersion: String) =
  printLine()
  println(s"Reporting failed community build projects using Scala $scalaVersion")
  val failedProjects = listFailedProjects(scalaVersion)
  if failedProjects.nonEmpty then reportCompilerRegressions(failedProjects, scalaVersion)
  printLine()
  esClient.close()
end report

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
    .await
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
    .await
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

private def reportCompilerRegressions(projects: Seq[FailedProject], scalaVersion: String): Unit =
  val failedProjectHistory = Future
    .traverse(projects) { fp =>
      Future { (fp, this.projectHistory(fp)) }
    }
    .await
    .toMap

  val failedProjects = projects.map(v => v.project -> v).toMap
  val projectHistory = failedProjectHistory.map { (key, value) => key.project -> value }
  val allHistory = projectHistory.values.flatten.toSeq

  printLine()
  println(s"Compiler regressions existing in Scala ${scalaVersion}:")
  println("List does not include runtime (tests), docs and infrastructure failures.")
  val alwaysFailing =
    StableScalaVersions.reverse
      .dropWhile(isVersionNewerOrEqualThen(_, scalaVersion))
      .foldLeft(failedProjects.keySet) { case (prev, scalaVersion) =>
        def regressionsSinceLastVersion(exactVersion: Boolean) = allHistory
          .filter(v =>
            v.scalaVersion == scalaVersion &&
              v.isCurrentVersion == exactVersion &&
              !v.compilerFailure && // was successfull in checked version
              prev.contains(v.project) // failed in newer version
          )
          .sortBy(_.project.searchName)
        val sameVersionRegressions = regressionsSinceLastVersion(exactVersion = true)
        val diffVersionRegressions = regressionsSinceLastVersion(exactVersion = false)
          .diff(sameVersionRegressions)
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

        if allRegressions.isEmpty then prev
        else
          val same = sameVersionRegressions
          val diff = diffVersionRegressions
          printLine()
          println(s"""Regressions since $BOLD$scalaVersion$RESET [${allRegressions.size}]:
             |Same versions[${same.size}]:    ${showFailed(same)}
             |Changed versions[${diff.size}]: ${showFailed(diff)}""".stripMargin)
          if diffVersionRegressions.nonEmpty then showDiffVersions()
          prev.diff(allRegressions.map(_.project).toSet)
      }
  if alwaysFailing.nonEmpty then
    val projectNames =
      alwaysFailing.map(_.orgRepoName).map(v => s"$BOLD$v$RESET").toSeq.sorted.mkString(", ")
    printLine()
    println(s"Always failing[${alwaysFailing.size}]: ${projectNames}")
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
