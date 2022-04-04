//> using scala "3.1.1"
//> using lib "com.sksamuel.elastic4s:elastic4s-client-esjava_2.13:7.17.2"
//> using lib "com.lihaoyi::os-lib:0.8.1"
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

import scala.language.implicitConversions
import scala.concurrent.*
import scala.concurrent.duration.*

given ExecutionContext = ExecutionContext.global

@main def reportBuildSummary(
    elasticsearchUrl: String,
    buildId: String,
    output: String // Path to output file or empty if stdout
) =
  val outputFile = Option.when(output.nonEmpty)(os.pwd / output)
  val clientConfig = new HttpClientConfigCallback {
    override def customizeHttpClient(
        httpClientBuilder: HttpAsyncClientBuilder
    ): HttpAsyncClientBuilder = {
      val creds = new BasicCredentialsProvider()
      creds.setCredentials(
        AuthScope.ANY,
        new UsernamePasswordCredentials(
          sys.env("ELASTIC_USERNAME"),
          sys.env("ELASTIC_PASSWORD")
        )
      )
      httpClientBuilder
        .setDefaultCredentialsProvider(creds)
        // Custom SSL context that would not require ES certificate
        .setSSLContext(unsafe.UnsafeSSLContext)
    }
  }

  val client = ElasticClient(
    JavaClient(
      ElasticProperties(elasticsearchUrl),
      clientConfig
    )
  )

  val AllBuilds = "allBuild"
  val BuildsById = "buildById"
  val ProjectBuildSummarriesIdx = "project-build-summary"

  def getBuildReport(): Future[BuildReport] = for {
    response <- client.execute(
      search(ProjectBuildSummarriesIdx)
        .query(termQuery("buildId", buildId))
        // Default limit equals 10k results. Should be more then ever needed
        .limit(10 * 1000)
        .sourceExclude("logs")
        .aggregations(
          globalAggregation(AllBuilds)
            .subAggregations(
              termsAgg(BuildsById, "buildId")
                .order(TermsOrder("_key", asc = false))
                .size(10)
            )
        )
    )
    result = response.result
  } yield BuildReport(
    buildId = buildId,
    buildIds = result.aggregations
      .global(AllBuilds)
      .terms(BuildsById)
      .buckets
      .map(_.key),
    projects = result.to[ProjectSummary]
  )

  val task = for {
    queryResult <- getBuildReport()
    report = showBuildReport(queryResult)
  } yield outputFile match {
    case None       => println(report)
    case Some(file) => os.write.over(target = file, data = report)
  }
  // Needed for applicaiton to exit
  task.onComplete { result =>
    result.failed.foreach(err => sys.error(err.toString))
    client.close()
  }
  Await.result(task, 5.minute)
end reportBuildSummary

def showBuildReport(report: BuildReport): String =
  val BuildReport(buildId, buildIds, projects) = report
  val Ident = "  "
  val Ident2 = Ident * 2
  val Ident3 = Ident * 3

  def showSuccessfullProject(project: ProjectSummary) =
    val ProjectSummary(name, version, _, _, modules) = project
    s"""$Ident+ $name: Version: $version""".stripMargin

  def showFailedProject(project: ProjectSummary) =
    val ProjectSummary(name, version, _, _, modules) = project
    def showModules(label: String, results: Seq[ModuleSummary])(
        show: ModuleSummary => String
    ) = 
      s"${Ident2}$label modules: ${results.size}" + {
        if results.isEmpty then ""
        else results.map(show).mkString("\n", "\n", "")
      }
    s"""$Ident- $name
       |${Ident2}Version: $version
       |${showModules("Successfull", project.successfullModules)(m =>
      s"$Ident2+ ${m.name}"
    )}
       |${showModules("Failed", project.failedModules)(showFailedModule(_))}
       |""".stripMargin

  def showFailedModule(module: ModuleSummary) =
    def showResults(res: List[String]) = res.mkString("[", ", ", "]")

    s"""${Ident2}- ${module.name}
    |${Ident3}Passed:  ${showResults(module.passed)}
    |${Ident3}Failed:  ${showResults(module.failed)}""".stripMargin

  if projects.isEmpty then
    s"""No results found for build '$buildId'
       |Latests known builds: ${buildIds
      .map("\'" + _ + "\'")
      .mkString(" ")}""".stripMargin
  else
    s"""Build Id:        $buildId
    |Projects count:  ${projects.size}
    |Scala version:   ${report.scalaVersion}
    |Failed projects: ${report.failedProjects.size} 
    |${report.failedProjects
      .map(showFailedProject(_))
      .mkString("\n")}
    |Successfull projects: ${report.sucsessfullProjects.size}
    |${report.sucsessfullProjects.map(showSuccessfullProject(_)).mkString("\n")}
    |""".stripMargin

case class BuildReport(
    buildId: String,
    buildIds: Seq[String],
    projects: Seq[ProjectSummary]
) {
  lazy val scalaVersion: String = projects.head.scalaVersion
  lazy val (sucsessfullProjects, failedProjects) =
    projects.partition(_.status == BuildStatus.Success)
}

enum BuildStatus:
  case Failure, Success
enum ModuleBuildResult:
  case Ok, Failed, Skipped

case class ModuleSummary(
    name: String,
    compile: ModuleBuildResult,
    doc: ModuleBuildResult,
    testsCompile: ModuleBuildResult,
    tests: ModuleBuildResult,
    publish: ModuleBuildResult
) {
  def hasFailure = productIterator.contains(ModuleBuildResult.Failed)
  private lazy val results = productElementNames
    .zip(productIterator)
    .collect { case (fieldName, result: ModuleBuildResult) =>
      (fieldName, result)
    }
    .toList
  private def collectResults(expected: ModuleBuildResult) =
    results.collect { case (name, `expected`) => name }.toList

  lazy val passed = collectResults(ModuleBuildResult.Ok)
  lazy val failed = collectResults(ModuleBuildResult.Failed)
  lazy val skipped = collectResults(ModuleBuildResult.Skipped)
}
case class ProjectSummary(
    projectName: String,
    version: String,
    scalaVersion: String,
    status: BuildStatus,
    projectsSummary: List[ModuleSummary]
) {
  lazy val (failedModules, successfullModules) =
    projectsSummary.partition(_.hasFailure)
}

given Conversion[String, ModuleBuildResult] = _ match {
  case "ok"      => ModuleBuildResult.Ok
  case "failed"  => ModuleBuildResult.Failed
  case "skipped" => ModuleBuildResult.Skipped
}

given HitReader[ProjectSummary] = (hit: Hit) => {
  val source = hit.sourceAsMap
  util.Try {
    val status = source("status") match {
      case "failure" => BuildStatus.Failure
      case "success" => BuildStatus.Success
    }
    val modulesSummary =
      for
        modueleSource <- source("summary")
          .asInstanceOf[List[Map[String, String]]]
      yield ModuleSummary(
        name = modueleSource("module"),
        compile = modueleSource("compile"),
        doc = modueleSource("doc"),
        testsCompile = modueleSource("test-compile"),
        tests = modueleSource("test"),
        publish = modueleSource("publish")
      )
    ProjectSummary(
      projectName = source("projectName").toString.replaceFirst("_", "/"),
      version = source("version").toString,
      scalaVersion = source("scalaVersion").toString,
      status = status,
      projectsSummary = modulesSummary
    )
  }

}

object unsafe {
  import javax.net.ssl.*
  import java.security.cert.X509Certificate

  lazy val UnsafeSSLContext = {
    object TrustAll extends X509TrustManager {
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

    val instance = SSLContext.getInstance("SSL")
    instance.init(null, Array(TrustAll), new java.security.SecureRandom())
    instance
  }

}
