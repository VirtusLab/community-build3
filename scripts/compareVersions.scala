//> using scala "3"
//> using dep "com.sksamuel.elastic4s:elastic4s-client-esjava_2.13:8.11.5"
//> using dep "org.slf4j:slf4j-simple:2.0.17"
//> using options -Wunused:all -deprecation

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

given ExecutionContext = ExecutionContext.global

val BuildSummariesIndex = "project-build-summary"
val DefaultTimeout = 5.minutes
val ElasticsearchHost = "scala3.westeurope.cloudapp.azure.com"
val ElasticsearchUrl = s"https://$ElasticsearchHost/data/"
val ElasticsearchCredentials = new UsernamePasswordCredentials(
  sys.env.getOrElse("ES_USERNAME", "elastic"),
  sys.env.getOrElse("ES_PASSWORD", "changeme")
)

// Switch for logging projects for which within a single Scala version we have multple versions of project with different build results (failure/success)
final val logMultipleVersions = false

@main def raportForScalaVersion(v1: String, v2: String) = scala.util.Using(getElasticsearchClient) {
  implicit esclient =>
    val projectsV1 = getProjectsInfo(scalaVersion = v1)
    val projectsV2 = getProjectsInfo(scalaVersion = v2)

    case class ProjectVersion(projectName: String, version: String)
    val sameProjectVersions =
      projectsV1
        .filter(v =>
          projectsV2.exists(e => e.projectName == v.projectName && e.version == v.version)
        )
        .map(v => ProjectVersion(v.projectName, v.version))
    val List(failingInV1, failingInV2) = List(projectsV1, projectsV2).map(
      _.filter(v => sameProjectVersions.contains(ProjectVersion(v.projectName, v.version)))
        .filter(_.status == "failure")
    )

    val failingInBoth = failingInV2
      .map(v =>
        failingInV1.find(e => e.projectName == v.projectName && e.version == v.version).zip(Some(v))
      )
      .flatten

    if (logMultipleVersions) {
      println()
      sameProjectVersions
        .groupBy(_.projectName)
        .filter(_._2.size > 1)
        .foreach { case (name, items) =>
          println(
            s"Project $name results exisiting in multiple version [${items.map(_.version).mkString(", ")}] in both Scala versions."
          )
        }
    }

    val newFailingInV2 = failingInV2
      .filter(v => !failingInV1.exists(_.projectName == v.projectName))

    val newPassingInV2 = failingInV1
      .filter(v => !failingInV2.exists(_.projectName == v.projectName))

    def showProject(v: ProjectInfo) =
      s"${v.buildTool} ${v.failureReasons.map(_.mkString(",")).getOrElse("")} ${v.scalaVersion} ${v.projectName} ${v.buildURL}"

    def showFailing(label: String, projects: Seq[ProjectInfo]) = {
      println()
      println("---------------------------")
      println(s"Projects failing in $label")
      println("By build tool:")
      projects.groupBy(_.buildTool).foreach { case (tool, toolProjects) =>
        println(s"  - $tool: ${toolProjects.size} (${fraction(toolProjects.size, projects.size)})")
      }
      projects
        .sortBy(v =>
          (v.buildTool, v.failureReasons.map(_.map(_.ordinal).mkString(",")), v.projectName)
        )
        .foreach(v => println(showProject(v)))
    }

    def showPassing(label: String, projects: Seq[ProjectInfo]) = {
      println()
      println("---------------------------")
      println(s"Projects passing in $label")
      println("By build tool:")
      projects.groupBy(_.buildTool).foreach { case (tool, toolProjects) =>
        println(s"  - $tool: ${toolProjects.size} (${fraction(toolProjects.size, projects.size)})")
      }
      projects
        .sortBy(v => (v.buildTool, v.projectName))
        .foreach(v => println(showProject(v)))
    }

    showFailing(v1, failingInV1)
    showFailing(v2, failingInV2)
    println()
    println("Failing in both versions")
    println("By build tool:")
    failingInBoth.map(_._1).groupBy(_.buildTool).foreach { case (tool, toolProjects) =>
      println(
        s"  - $tool: ${toolProjects.size} (${fraction(toolProjects.size, failingInBoth.size)})"
      )
    }
    failingInBoth.sortBy(_._1.projectName).foreach { case (v1, v2) =>
      println(v1.projectName + ":")
      List(v1, v2).foreach { v => println(s"  - ${showProject(v)}") }
    }
    showFailing(s"$v2 since $v1", newFailingInV2)
    showPassing(s"$v2 but not int $v1", newPassingInV2)

    def reasonStats(projects: Seq[ProjectInfo]) = FailureReason.values
      .map(reason => reason -> projects.count(_.failureReasons.exists(_.contains(reason))))
      .map { case (reason, count) => s"$reason=$count (${fraction(count, projects.size)})" }
      .mkString(", ")

    def fraction(v1: Int, v2: Int): String = f"${v1.toFloat / v2.toFloat * 100}%2.2f%%"

    println(s"""
    |--------------------------------------
    |Query results:
    |projects results for $v1:  ${projectsV1.size}
    |projects results for $v2:  ${projectsV2.size}
    |
    |Comparsion results
    |Projects with the same version: ${sameProjectVersions.size} (used for comparsion)
    |By build tool:
    |${projectsV1
      .filter(v => sameProjectVersions.contains(ProjectVersion(v.projectName, v.version)))
      .groupBy(_.buildTool)
      .map { case (tool, toolProjects) =>
        s"  - $tool: ${toolProjects.size} (${fraction(toolProjects.size, sameProjectVersions.size)})"
      }
      .mkString("\n")}
    |Failing in $v1:  ${failingInV1.size} (${fraction(
      failingInV1.size,
      sameProjectVersions.size
    )}), reasons: ${reasonStats(failingInV1)}
    |Failing in $v2:  ${failingInV2.size} (${fraction(
      failingInV2.size,
      sameProjectVersions.size
    )}), reasons: ${reasonStats(failingInV2)}
    |Failing in both: ${failingInBoth.size} (${fraction(
      failingInBoth.size,
      sameProjectVersions.size
    )}): 
    |  - reasons $v1: ${reasonStats(failingInBoth.map(_._1))}
    |  - reasons $v2: ${reasonStats(failingInBoth.map(_._2))}
    |New failing in $v2 since $v1: ${newFailingInV2.size}, reasons: ${reasonStats(newFailingInV2)}
    |New passing in $v2 since $v1: ${newPassingInV2.size}, reasons previously: ${reasonStats(
      newPassingInV2
    )}
    |""".stripMargin)
}

def getElasticsearchClient = {
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

case class ProjectInfo(
    projectName: String,
    version: String,
    scalaVersion: String,
    status: String,
    buildTool: String,
    buildId: String,
    buildURL: String,
    failureReasons: Option[List[FailureReason]]
)
def getProjectsInfo(scalaVersion: String)(using esClient: ElasticClient): Seq[ProjectInfo] =
  esClient
    .execute {
      search(BuildSummariesIndex)
        .query(
          boolQuery()
            .must(
              Seq(
                termQuery("scalaVersion", scalaVersion)
              )
            )
        )
        .size(10 * 1000)
        .sourceInclude(
          "projectName",
          "buildTool",
          "buildId",
          "scalaVersion",
          "version",
          "status",
          "buildURL",
          "summary"
        )
        .sortBy(fieldSort("projectName"), fieldSort("timestamp").desc())
    }
    .await(using DefaultTimeout)
    .fold(
      ex => sys.error(s"Failed to execute query: scalaVersion=${scalaVersion}, ex: $ex"),
      _.hits.hits
        .map { item =>
          def string(field: String) = item.sourceField(field).asInstanceOf[String]
          def stringOpt(field: String) = item.sourceFieldOpt(field).asInstanceOf[Option[String]]
          def summary = item.sourceField("summary").asInstanceOf[List[SourceFields]]

          ProjectInfo(
            projectName = string("projectName"),
            version = string("version"),
            scalaVersion = string("scalaVersion"),
            status = string("status"),
            buildTool = stringOpt("buildTool").getOrElse("unknown"),
            buildId = string("buildId"),
            buildURL = string("buildURL"),
            failureReasons = Option.when(string("status").contains("failure")) {
              val reasons = List(
                Option.when(summary.compilerFailure)(FailureReason.Compilation),
                Option.when(summary.testsFailure)(FailureReason.Tests),
                Option.when(summary.docFailure)(FailureReason.Scaladoc),
                Option.when(summary.publishFailure)(FailureReason.Publish)
              ).flatten
              if reasons.isEmpty then List(FailureReason.Other)
              else reasons
            }
          )
        }
        .groupBy(v => (v.projectName, v.version))
        .map { case (_, items) => items.find(_.status == "success").getOrElse(items.head) }
        .groupBy(_.projectName)
        .flatMap { case (_, items) =>
          val statusValues = items.map(_.status).toSeq.distinct.size
          if (logMultipleVersions && items.size > 1 && statusValues > 1) then
            println(
              s"Found multiple versions of ${items.head.projectName} with different results ${items
                .map(v => s"${v.version}:${v.status}")
                .mkString(" ")} for Scala ${scalaVersion}"
            )
          items
        }
        .toSeq
    )

type SourceFields = Map[String, AnyRef]
// Unsafe API to check project summary results in all the modules
enum FailureReason:
  case Compilation, Tests, Publish, Scaladoc, Other

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
