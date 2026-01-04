package dashboard.data

import cats.effect.{IO, Resource}
import scribe.cats.{io => log}

import com.sksamuel.elastic4s.{ElasticClient, ElasticNodeEndpoint}
import com.sksamuel.elastic4s.http4s.Http4sClient
import com.sksamuel.elastic4s.ElasticDsl.*
import com.sksamuel.elastic4s.requests.searches.*
import com.sksamuel.elastic4s.requests.searches.aggs.TermsOrder
import com.sksamuel.elastic4s.requests.searches.aggs.responses.bucket.Terms
import com.sksamuel.elastic4s.requests.searches.collapse.CollapseRequest

import org.http4s.client.Client
import org.http4s.headers.Authorization
import org.http4s.{BasicCredentials, Uri}

import java.time.Instant

import dashboard.ElasticsearchConfig
import dashboard.core.*

/** Elasticsearch client for querying build results */
trait ElasticsearchClient:
  /** Validate connectivity to Elasticsearch */
  def validateConnection(): IO[Unit]

  /** Get all builds for a scala version */
  def getBuildsByScalaVersion(scalaVersion: String): IO[List[BuildResult]]

  /** Get all builds for a specific buildId */
  def getBuildsByBuildId(buildId: String): IO[List[BuildResult]]

  /** Get project history across all scala versions */
  def getProjectHistory(projectName: ProjectName): IO[List[ProjectHistoryEntry]]

  /** Get a single build's full details including logs */
  def getBuildDetails(projectName: ProjectName, buildId: String): IO[Option[BuildResult]]

  /** Get latest builds (one per project for the most recent buildId) */
  def getLatestBuilds(): IO[List[BuildResult]]

  /** List all projects with their latest build status */
  def listAllProjects(): IO[ProjectsList]

  /** List available scala versions */
  def listScalaVersions(): IO[List[String]]

  /** List available build IDs for a scala version */
  def listBuildIds(scalaVersion: Option[String]): IO[List[String]]

object ElasticsearchClient:
  private val BuildSummariesIndex = "project-build-summary"
  private val MaxResults = 10000

  def resource(config: ElasticsearchConfig, httpClient: Client[IO]): Resource[IO, ElasticsearchClient] =
    val endpoint = ElasticNodeEndpoint(
      protocol = "https",
      host = config.host,
      port = config.port,
      prefix = config.pathPrefix
    )

    // Add basic auth, ensure HTTPS, and optionally prepend path prefix for all requests
    // (the elastic4s http4s client doesn't respect protocol or prefix from ElasticNodeEndpoint)
    val authHeader = Authorization(BasicCredentials(config.username, config.password))
    val authedClient = Client[IO] { req =>
      val currentPath = req.uri.path.renderString
      val finalPath = config.pathPrefix match
        case Some(prefix) => Uri.Path.unsafeFromString(prefix + currentPath)
        case None         => req.uri.path
      val fixedUri = req.uri.copy(
        scheme = Some(Uri.Scheme.https),
        path = finalPath
      )
      val authedReq = req.withUri(fixedUri).putHeaders(authHeader)
      scribe.debug(s"ES ${authedReq.method} ${authedReq.uri}")
      httpClient.run(authedReq)
    }

    for
      _ <- Resource.eval(
        log.info(s"Connecting to Elasticsearch: ${config.host}:${config.port}${config.pathPrefix.getOrElse("")}")
      )
      http4sClient = Http4sClient[IO](authedClient, endpoint)
    yield new ElasticsearchClientImpl(ElasticClient[IO](http4sClient))

  private class ElasticsearchClientImpl(client: ElasticClient[IO]) extends ElasticsearchClient:

    override def validateConnection(): IO[Unit] =
      client
        .execute(clusterHealth())
        .flatMap:
          case response if response.isSuccess =>
            val health = response.result
            log.info(s"Elasticsearch connected: cluster '${health.clusterName}', status: ${health.status}") *>
              IO.whenA(health.status == "red")(
                IO.raiseError(RuntimeException(s"Elasticsearch cluster health is RED"))
              )
          case response =>
            val errorInfo =
              s"status=${response.status}, reason=${response.error.reason}, type=${response.error.`type`}, body=${response.body.getOrElse("none")}"
            IO.raiseError(RuntimeException(s"Failed to connect to Elasticsearch: $errorInfo"))

    override def getBuildsByScalaVersion(scalaVersion: String): IO[List[BuildResult]] =
      executeSearch:
        search(BuildSummariesIndex)
          .query(termQuery("scalaVersion", scalaVersion))
          .size(MaxResults)
          .sourceInclude(standardFields)
          .sortBy(fieldSort("projectName"), fieldSort("timestamp").desc())

    override def getBuildsByBuildId(buildId: String): IO[List[BuildResult]] =
      executeSearch:
        search(BuildSummariesIndex)
          .query(termQuery("buildId", buildId))
          .size(MaxResults)
          .sourceInclude(standardFields)
          .sortBy(fieldSort("projectName"), fieldSort("timestamp").desc())

    override def getProjectHistory(projectName: ProjectName): IO[List[ProjectHistoryEntry]] =
      val query = search(BuildSummariesIndex)
        .query(
          boolQuery()
            .should(
              termQuery("projectName", projectName.searchName),
              termQuery("projectName", projectName.legacySearchName)
            )
            .minimumShouldMatch(1)
        )
        .size(MaxResults)
        .sourceInclude(
          "projectName",
          "scalaVersion",
          "version",
          "status",
          "summary",
          "timestamp",
          "buildURL",
          "buildId"
        )
        .sortBy(fieldSort("timestamp").desc())

      executeRaw(query).map: response =>
        response.hits.hits.toList.flatMap: hit =>
          val fields = hit.sourceAsMap
          for
            name <- fields.get("projectName").map(_.toString).flatMap(s => ProjectName(s).toOption)
            scalaVersion <- fields.get("scalaVersion").map(_.toString)
            version <- fields.get("version").map(_.toString)
            status <- fields.get("status").map(s => BuildStatus.fromString(s.toString))
            timestamp <- fields.get("timestamp").map(t => Instant.parse(t.toString))
            buildURL <- fields.get("buildURL").map(_.toString)
            buildId <- fields.get("buildId").map(_.toString)
          yield
            val summary = extractSummary(fields)
            val reasons = computeFailureReasons(status, summary)
            ProjectHistoryEntry(name, scalaVersion, version, status, reasons, timestamp, buildURL, buildId)

    override def getBuildDetails(projectName: ProjectName, buildId: String): IO[Option[BuildResult]] =
      val query = search(BuildSummariesIndex)
        .query(
          boolQuery().must(
            boolQuery()
              .should(
                termQuery("projectName", projectName.searchName),
                termQuery("projectName", projectName.legacySearchName)
              )
              .minimumShouldMatch(1),
            termQuery("buildId", buildId)
          )
        )
        .size(1)
        .sourceInclude(standardFields :+ "logs")

      executeRaw(query).map: response =>
        response.hits.hits.headOption.flatMap(hit => parseBuildResult(hit.sourceAsMap))

    override def getLatestBuilds(): IO[List[BuildResult]] =
      // First get the most recent buildId
      val latestBuildIdQuery = search(BuildSummariesIndex)
        .size(0)
        .aggs(
          termsAgg("buildIds", "buildId")
            .order(TermsOrder("maxTimestamp", asc = false))
            .subaggs(maxAgg("maxTimestamp", "timestamp"))
            .size(1)
        )

      for
        aggResponse <- executeRaw(latestBuildIdQuery)
        latestBuildId = aggResponse.aggs
          .result[Terms]("buildIds")
          .buckets
          .headOption
          .map(_.key)
        builds <- latestBuildId match
          case Some(buildId) => getBuildsByBuildId(buildId)
          case None          => IO.pure(Nil)
      yield builds

    override def listAllProjects(): IO[ProjectsList] =
      // Get one result per project using collapse (latest by timestamp)
      val query = search(BuildSummariesIndex)
        .size(MaxResults)
        .sourceInclude("projectName", "scalaVersion", "version", "status", "summary", "timestamp", "buildId")
        .sortBy(fieldSort("timestamp").desc())
        .collapse(CollapseRequest("projectName"))

      executeRaw(query).map: response =>
        val summaries = response.hits.hits.toList.flatMap: hit =>
          val fields = hit.sourceAsMap
          for
            name <- fields.get("projectName").map(_.toString).flatMap(s => ProjectName(s).toOption)
            scalaVersion <- fields.get("scalaVersion").map(_.toString)
            version <- fields.get("version").map(_.toString)
            status <- fields.get("status").map(s => BuildStatus.fromString(s.toString))
            timestamp <- fields.get("timestamp").map(t => Instant.parse(t.toString))
            buildId <- fields.get("buildId").map(_.toString)
          yield
            val summary = extractSummary(fields)
            val reasons = computeFailureReasons(status, summary)
            ProjectSummary(name, scalaVersion, version, status, reasons, timestamp, buildId)

        // Sort by name alphabetically
        val sorted = summaries.sortBy(_.projectName: String)
        val passing = sorted.count(_.status == BuildStatus.Success)
        val failing = sorted.count(_.status == BuildStatus.Failure)
        ProjectsList(sorted, sorted.length, passing, failing)

    override def listScalaVersions(): IO[List[String]] =
      val query = search(BuildSummariesIndex)
        .size(0)
        .aggs(
          termsAgg("versions", "scalaVersion")
            .size(1000)
        )

      executeRaw(query).map: response =>
        val versions = response.aggs
          .result[Terms]("versions")
          .buckets
          .map(_.key)
          .toList
        // Sort using SemVersion ordering (reversed for descending order)
        given Ordering[String] = (a, b) => SemVersion.given_Ordering_SemVersion.compare(b, a)
        versions.sorted

    override def listBuildIds(scalaVersion: Option[String]): IO[List[String]] =
      val baseQuery = search(BuildSummariesIndex).size(0)
      val query = scalaVersion match
        case Some(sv) => baseQuery.query(termQuery("scalaVersion", sv))
        case None     => baseQuery

      val withAggs = query.aggs(
        termsAgg("buildIds", "buildId")
          .order(TermsOrder("maxTimestamp", asc = false))
          .subaggs(maxAgg("maxTimestamp", "timestamp"))
          .size(100)
      )

      executeRaw(withAggs).map: response =>
        response.aggs
          .result[Terms]("buildIds")
          .buckets
          .map(_.key)
          .toList

    private def executeSearch(request: SearchRequest): IO[List[BuildResult]] =
      executeRaw(request).map: response =>
        response.hits.hits.toList.flatMap(hit => parseBuildResult(hit.sourceAsMap))

    private def executeRaw(request: SearchRequest): IO[SearchResponse] =
      client
        .execute(request)
        .flatMap:
          case response if response.isSuccess => IO.pure(response.result)
          case response                       => IO.raiseError(RuntimeException(s"ES query failed: ${response.error}"))

    private val standardFields = Seq(
      "projectName",
      "version",
      "scalaVersion",
      "buildId",
      "buildTool",
      "status",
      "buildURL",
      "timestamp",
      "summary"
    )

    private def parseBuildResult(fields: Map[String, Any]): Option[BuildResult] =
      for
        name <- fields.get("projectName").map(_.toString).flatMap(s => ProjectName(s).toOption)
        version <- fields.get("version").map(_.toString)
        scalaVersion <- fields.get("scalaVersion").map(_.toString)
        buildId <- fields.get("buildId").map(_.toString)
        status <- fields.get("status").map(s => BuildStatus.fromString(s.toString))
        timestamp <- fields.get("timestamp").map(t => Instant.parse(t.toString))
        buildURL <- fields.get("buildURL").map(_.toString)
      yield
        val buildTool = fields.get("buildTool").map(s => BuildTool.fromString(s.toString)).getOrElse(BuildTool.Unknown)
        val summary = extractSummary(fields)
        val logs = fields.get("logs").map(_.toString)
        BuildResult(name, version, scalaVersion, buildId, buildTool, status, buildURL, timestamp, summary, logs)

    private def extractSummary(fields: Map[String, Any]): List[ModuleSummary] =
      fields.get("summary") match
        case Some(list: List[?]) =>
          list.flatMap:
            case m: Map[?, ?] =>
              val map = m.asInstanceOf[Map[String, Any]]
              for module <- map.get("module").map(_.toString)
              yield ModuleSummary(
                module = module,
                compile = extractTaskResult(map, "compile"),
                testCompile = extractTaskResult(map, "test-compile"),
                test = extractTestResult(map),
                doc = extractTaskResult(map, "doc"),
                publish = extractTaskResult(map, "publish")
              )
            case _ => None
        case _ => Nil

    private def extractTaskResult(map: Map[String, Any], key: String): TaskResult =
      map.get(key) match
        case Some(task: Map[?, ?]) =>
          val taskMap = task.asInstanceOf[Map[String, Any]]
          TaskResult(
            status = taskMap.get("status").map(s => TaskStatus.fromString(s.toString)).getOrElse(TaskStatus.Skipped),
            errors = taskMap.get("errors").map(_.toString.toInt).getOrElse(0),
            warnings = taskMap.get("warnings").map(_.toString.toInt).getOrElse(0),
            tookMs = taskMap.get("tookMs").map(_.toString.toLong).getOrElse(0L)
          )
        case _ => TaskResult(TaskStatus.Skipped)

    private def extractTestResult(map: Map[String, Any]): TestResult =
      map.get("test") match
        case Some(test: Map[?, ?]) =>
          val testMap = test.asInstanceOf[Map[String, Any]]
          TestResult(
            status = testMap.get("status").map(s => TaskStatus.fromString(s.toString)).getOrElse(TaskStatus.Skipped),
            passed = testMap.get("passed").map(_.toString.toInt).getOrElse(0),
            failed = testMap.get("failed").map(_.toString.toInt).getOrElse(0),
            ignored = testMap.get("ignored").map(_.toString.toInt).getOrElse(0),
            skipped = testMap.get("skipped").map(_.toString.toInt).getOrElse(0),
            total = testMap.get("total").map(_.toString.toLong).getOrElse(0L),
            tookMs = testMap.get("tookMs").map(_.toString.toLong).getOrElse(0L)
          )
        case _ => TestResult(TaskStatus.Skipped)

    private def computeFailureReasons(status: BuildStatus, summary: List[ModuleSummary]): List[FailureReason] =
      if status == BuildStatus.Success then Nil
      else if summary.isEmpty then List(FailureReason.Build)
      else
        val reasons = List(
          Option.when(summary.exists(_.hasCompileFailure))(FailureReason.Compilation),
          Option.when(summary.exists(_.hasTestFailure))(FailureReason.Tests),
          Option.when(summary.exists(_.hasDocFailure))(FailureReason.Scaladoc),
          Option.when(summary.exists(_.hasPublishFailure))(FailureReason.Publish)
        ).flatten
        if reasons.isEmpty then List(FailureReason.Other) else reasons
