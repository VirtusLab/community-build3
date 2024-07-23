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

given scala.util.CommandLineParser.FromString[os.Path] = path =>
  try os.Path(path)
  catch { case _: Exception => os.RelPath(path).resolveFrom(os.pwd) }

@main def raport(version: String, buildConfigFile: os.Path) = try {
  val projectsInConfig = ujson
    .read:
      os.read(buildConfigFile).mkString
    .obj
    .keySet
  println(s"Config contains ${projectsInConfig.size} projects")
  
  val reportedProjects = projectsForScalaVersion(version)
  println(s"Database contains reports for ${reportedProjects.size} projects")

  val missingReports = projectsInConfig.diff(reportedProjects)
  val unknownReports = reportedProjects.diff(projectsInConfig)
  println(s"Missing reports for ${missingReports.size} projects")
  missingReports.toSeq.sorted.zipWithIndex.foreach: (project, idx) =>
    println(s"${idx + 1}.\t$project")
  println(s"Reports contains ${unknownReports.size} projects not listed in config")
  unknownReports.toSeq.sorted.zipWithIndex.foreach: (project, idx) =>
    println(s"${idx + 1}.\t$project")

} finally esClient.close()
  

def projectsForScalaVersion(scalaVersion: String) = 
   esClient
    .execute:
      search(BuildSummariesIndex)
        .query(
          termQuery("scalaVersion", scalaVersion)
        )
        .size(10 * 1000)
        .sourceInclude("projectName")
    .await(DefaultTimeout)
    .fold(
      err => sys.error(s"Failed to list projects from Elasticsearch: ${err.error}"),
      result => 
        result.hits.hits.map(_.sourceField("projectName").asInstanceOf[String]).toSet
    )