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
import scala.reflect.ClassTag

given ExecutionContext = ExecutionContext.global
import FromMap.given

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
    val ProjectSummary(name, version, _, _, _, modules) = project
    s"""$Ident+ $name: Version: $version""".stripMargin

  def showFailedProject(project: ProjectSummary) =
    val ProjectSummary(name, version, _, _, buildUrl, modules) = project
    def showModules(label: String, results: Seq[ModuleSummary])(
        show: ModuleSummary => String
    ) =
      s"${Ident2}$label modules: ${results.size}" + {
        if results.isEmpty then ""
        else results.map(show).mkString("\n", "\n", "")
      }
    s"""$Ident- $name
       |${Ident2}Version: $version
       |${Ident2}Build URL: $buildUrl
       |${showModules("Successfull", project.successfullModules)(m => s"$Ident2+ ${m.name}")}
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
enum StepStatus:
  case Ok, Failed, Skipped

sealed trait StepResult {
  def status: StepStatus
}

// Currently we don't use any of the other result fields
case class CompileResult(status: StepStatus) extends StepResult
case class DocsResult(status: StepStatus) extends StepResult
case class TestsResult(status: StepStatus) extends StepResult
case class PublishResult(status: StepStatus) extends StepResult

case class ModuleSummary(
    name: String,
    compile: CompileResult,
    doc: DocsResult,
    testsCompile: CompileResult,
    tests: TestsResult,
    publish: PublishResult
) {
  def hasFailure = results.exists(_._2 == StepStatus.Failed)
  private lazy val results: List[(String, StepStatus)] = productElementNames
    .zip(productIterator)
    .collect { case (fieldName, result: StepResult) =>
      (fieldName, result.status)
    }
    .toList
  private def collectResults(expected: StepStatus) =
    results.collect { case (name, `expected`) => name }.toList

  lazy val passed = collectResults(StepStatus.Ok)
  lazy val failed = collectResults(StepStatus.Failed)
  lazy val skipped = collectResults(StepStatus.Skipped)
}
case class ProjectSummary(
    projectName: String,
    version: String,
    scalaVersion: String,
    status: BuildStatus,
    buildUrl: String,
    projectsSummary: List[ModuleSummary]
) {
  lazy val (failedModules, successfullModules) =
    projectsSummary.partition(_.hasFailure)
}

given Conversion[String, StepStatus] = _ match {
  case "ok"      => StepStatus.Ok
  case "failed"  => StepStatus.Failed
  case "skipped" => StepStatus.Skipped
}

given HitReader[ProjectSummary] = (hit: Hit) => {
  val source = hit.sourceAsMap
  util.Try {
    val status = source("status") match {
      case "failure" => BuildStatus.Failure
      case "success" => BuildStatus.Success
    }

    val modulesSummary =
      for moduleSource <- source("summary").asInstanceOf[List[Map[String, AnyRef]]]
      yield
        def fromMap[T](field: String)(using conv: FromMap[T]) =
          moduleSource.get(field) match {
            case Some(m: Map[_, _]) =>
              conv.fromMap(m.asInstanceOf[Map[String, AnyRef]]) match {
                case Right(value) => value
                case Left(reason) => sys.error(s"Cannot decode '$field', reason: ${reason}")
              }
            case Some(m) => sys.error(s"Field '$field' is not a map: ${m}")
            case None    => sys.error(s"No field with name '$field'")
          }

        ModuleSummary(
          name = moduleSource("module").toString,
          compile = fromMap[CompileResult]("compile"),
          doc = fromMap[DocsResult]("doc"),
          testsCompile = fromMap[CompileResult]("test-compile"),
          tests = fromMap[TestsResult]("test"),
          publish = fromMap[PublishResult]("publish")
        )
    ProjectSummary(
      projectName = source("projectName").toString.replaceFirst("_", "/"),
      version = source("version").toString,
      scalaVersion = source("scalaVersion").toString,
      status = status,
      buildUrl = source("buildURL").toString,
      projectsSummary = modulesSummary
    )
  }
}

sealed trait FromMap[T]:
  import FromMap.*
  def fromMap(source: SourceMap): Result[T]

object FromMap {
  type SourceMap = Map[String, AnyRef]
  type Result[T] = Either[FromMap.ConversionFailure, T]
  sealed trait ConversionFailure
  case class FieldMissing(field: String) extends ConversionFailure
  case class IncorrectMapping(expected: Class[_], got: Class[_]) extends ConversionFailure

  extension (source: SourceMap) {
    def field(name: String): Result[AnyRef] =
      source.get(name).toRight(left = FieldMissing(name))
    def mapTo[T: FromMap]: Result[T] = summon[FromMap[T]].fromMap(source)
  }

  extension (value: Result[AnyRef]) {
    inline def as[T: ClassTag]: Result[T] = value.flatMap {
      case instance: T => Right(instance.asInstanceOf[T])
      case other =>
        Left(IncorrectMapping(expected = summon[ClassTag[T]].runtimeClass, got = other.getClass))
    }
  }

  given FromMap[CompileResult] with
    def fromMap(source: SourceMap): Result[CompileResult] =
      for status <- source.field("status").as[String]
      yield CompileResult(status = status: StepStatus)

  given FromMap[DocsResult] with
    def fromMap(source: SourceMap): Result[DocsResult] =
      for status <- source.field("status").as[String]
      yield DocsResult(status = status: StepStatus)

  given FromMap[TestsResult] with
    def fromMap(source: SourceMap): Result[TestsResult] =
      for status <- source.field("status").as[String]
      yield TestsResult(
        status = status: StepStatus
      )

  given FromMap[PublishResult] with
    def fromMap(source: SourceMap): Result[PublishResult] =
      for status <- source.field("status").as[String]
      yield PublishResult(status: StepStatus)

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
