//> using scala "3"
//> using dep "com.sksamuel.elastic4s:elastic4s-client-esjava_2.13:8.11.5"
//> using dep "org.slf4j:slf4j-simple:2.0.17"
//> using toolkit default
//> using options -Wunused:all

import com.sksamuel.elastic4s
import elastic4s.*
import elastic4s.http.JavaClient
import elastic4s.ElasticDsl.*
import org.elasticsearch.client.RestClientBuilder.{HttpClientConfigCallback, RequestConfigCallback}
import org.apache.http.impl.nio.client.*
import org.apache.http.impl.client.BasicCredentialsProvider
import org.apache.http.auth.*
import org.apache.http.client.config.RequestConfig

import scala.concurrent.*
import scala.concurrent.duration.*
import org.elasticsearch.client.RestClient
import org.apache.http.HttpHost

given ExecutionContext = ExecutionContext.global

val BuildSummariesIndex = "project-build-summary"
val DefaultTimeout = 10.minutes
val ElasticsearchHost = "scala3.westeurope.cloudapp.azure.com"
val ElasticsearchCredentials = new UsernamePasswordCredentials(
  sys.env.getOrElse("ES_USERNAME", "elastic"),
  sys.env.getOrElse("ES_PASSWORD", "changeme")
)

def createElasticsearchClient: ElasticClient = {
  val clientConfig = new HttpClientConfigCallback {
    override def customizeHttpClient(
        httpClientBuilder: HttpAsyncClientBuilder
    ): HttpAsyncClientBuilder = {
      val creds = new BasicCredentialsProvider()
      creds.setCredentials(AuthScope.ANY, ElasticsearchCredentials)
      httpClientBuilder.setDefaultCredentialsProvider(creds)
    }
  }

  val requestConfig = new RequestConfigCallback {
    override def customizeRequestConfig(
        requestConfigBuilder: RequestConfig.Builder
    ): RequestConfig.Builder = {
      val timeoutMs = 5.minutes.toMillis.toInt
      requestConfigBuilder
        .setSocketTimeout(timeoutMs)
        .setConnectTimeout(timeoutMs)
        .setConnectionRequestTimeout(timeoutMs)
    }
  }

  ElasticClient(
    JavaClient.fromRestClient(
      RestClient
        .builder(HttpHost(ElasticsearchHost, -1, "https"))
        .setPathPrefix("/data")
        .setHttpClientConfigCallback(clientConfig)
        .setRequestConfigCallback(requestConfig)
        .build()
    )
  )
}

@main def removeByScalaVersion(
    scalaVersion: String = "s-RC1-bin-531f29b-SNAPSHOT",
    dryRun: Boolean = true
): Unit = util
  .Using(createElasticsearchClient) { client =>
    val query = termQuery("scalaVersion", scalaVersion)

    println(s"Looking for documents with scalaVersion: $scalaVersion")
    println(s"Dry run mode: $dryRun")
    println("Continue? (y/n)")
    if !io.StdIn.readBoolean() then
      println("Aborting...")
      sys.exit(0)

    // First, count how many documents match
    val countResult = client
      .execute {
        count(BuildSummariesIndex).query(query)
      }
      .await(using DefaultTimeout)

    countResult.fold(
      err => sys.error(s"Failed to count documents: ${err.error}"),
      count =>
        println(s"Found ${count.count} documents with scalaVersion=$scalaVersion")
        println()

        if count.count == 0 then println("No documents to delete.")
        else if dryRun then
          println("DRY RUN: Would delete the above documents.")
          println("Run with --dry-run=false to actually perform the deletion.")
        else
          println("Deleting documents...")

          val deleteResult = client
            .execute {
              deleteByQuery(BuildSummariesIndex, query)
                .proceedOnConflicts(true)
                .waitForCompletion(true)
                .refreshImmediately
            }
            .await(using DefaultTimeout)

          deleteResult.fold(
            err => sys.error(s"Failed to delete documents: ${err}"),
            result =>
              result match
                case Left(response) =>
                  println(s"Successfully deleted ${response.deleted} documents")
                  if response.versionConflicts > 0 then
                    println(s"Warning: ${response.versionConflicts} version conflicts occurred (proceeded anyway)")
                  if response.failures.nonEmpty then
                    println(s"Warning: ${response.failures.size} failures occurred:")
                    response.failures.take(10).foreach { failure =>
                      println(s"  - Failure: $failure")
                    }
                case Right(taskResponse) =>
                  println(s"Delete task created: ${taskResponse.nodeId}:${taskResponse.taskId}")
          )
    )
  }
  .fold(
    err => sys.error(s"Failed to delete documents: $err"),
    _ => println("Documents deleted successfully")
  )
