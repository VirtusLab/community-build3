//> using dep "com.sksamuel.elastic4s:elastic4s-client-esjava_2.13:8.11.5"
//> using dep "org.slf4j:slf4j-simple:2.0.17"
//> using toolkit 0.7.0
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

val BuildSummariesIndex = "project-build-summary"
val DefaultTimeout = 5.minutes
val ElasticsearchHost = "scala3.westeurope.cloudapp.azure.com"
val ElasticsearchUrl = s"https://$ElasticsearchHost/data/"
val ElasticsearchCredentials = new UsernamePasswordCredentials(
  sys.env.getOrElse("ES_USERNAME", "elastic"),
  sys.env.getOrElse("ES_PASSWORD", "changeme")
)

@main def esPlayground = try {
  val ScalaVersion = "3.6.0-RC1-bin-20240826-c0cfd0f-NIGHTLY"
  esClient.execute:
    search(BuildSummariesIndex)
      .query(
        boolQuery().must(
          termQuery("scalaVersion", ScalaVersion),
          termQuery("buildTool", "mill")
        ))
      .size(10 * 1000)
      // .size(1)F
      .sourceInclude("projectName", "buildTool", "summary")
  .await(using DefaultTimeout)
  .fold(
    err => sys.error(s"Failed to list projects from Elasticsearch: ${err.error}"),
    result => 
      extension (value: Any) infix def as[T] = value.asInstanceOf[T]
      type TestResults = (skipped: Int, ignored: Int, failed: Int, passed: Int)
      val parsed = for 
        hit <- result.hits.hits.to(LazyList)
        project = hit.sourceField("projectName").as[String]
        buildTool = hit.sourceField("buildTool").as[String]
        summary <- hit.sourceField("summary").as[Seq[Map[String, Any]]]
        tests = summary("test").as[Map[String, Any]]
        skipped = tests("skipped").as[Int]
        ignored = tests("ignored").as[Int]
        failed = tests("failed").as[Int]
        passed = tests("passed").as[Int]
      yield (project = project, buildTool = buildTool, tests = (skipped = skipped, ignored = ignored, failed = failed, passed = passed): TestResults) 

      parsed
      .groupBy(_.project)
      .map(_._1)
      .toSeq
      .sorted
      .foreach(println)
      // parsed
      //  .mapValues: grouped =>
      //   (project = grouped.head.project, buildTool = grouped.head.buildTool, tests = grouped.map(_.tests).reduce: (l, r) =>
      //     (
      //       skipped = l.skipped + r.skipped, 
      //       ignored = l.ignored + r.ignored,
      //       failed = l.failed + r.failed,
      //       passed = l.passed + r.passed
      //       ))
      //  .values
      //  .foreach: v =>
      //   println(s"${v.project},${v.buildTool},${v.tests.skipped},${v.tests.ignored},${v.tests.failed},${v.tests.passed}")
  )
} finally esClient.close()
  

given ExecutionContext = ExecutionContext.global

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


