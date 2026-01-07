package dashboard.web

import cats.effect.{Clock, IO}
import cats.syntax.all.*
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.http4s.{headers, Uri, UrlForm}
import org.typelevel.ci.CIStringSyntax
import sttp.tapir.server.http4s.Http4sServerInterpreter
import sttp.tapir.swagger.bundle.SwaggerInterpreter
import scribe.cats.{io => log}

import dashboard.api.*
import dashboard.auth.GitHubOAuth
import dashboard.core.*
import dashboard.data.{
  BuildsCache,
  CacheManager,
  ComparisonCache,
  FailureStreaksCache,
  HistoryCache,
  LogsCache,
  ProjectsCache,
  ProjectNote,
  ElasticsearchClient,
  SqliteRepository
}

/** HTTP routes combining Tapir API endpoints and HTML pages */
object Routes:

  /** Request logging middleware */
  private def withLogging(routes: HttpRoutes[IO]): HttpRoutes[IO] =
    cats.data.Kleisli { (req: Request[IO]) =>
      cats.data.OptionT(
        for
          start <- Clock[IO].monotonic
          context =
            s"${req.method} ${req.uri.path}${req.uri.query.renderString.some.filter(_.nonEmpty).map("?" + _).getOrElse("")}"
          _ <- log.info(s"→ $context")
          responseOpt <- routes.run(req).value
          end <- Clock[IO].monotonic
          duration = end - start
          _ <- responseOpt match
            case Some(response) =>
              val statusEmoji = if response.status.isSuccess then "✓" else "✗"
              log.info(s"$statusEmoji $context → ${response.status.code} (${duration.toMillis}ms)")
            case None =>
              IO.unit
        yield responseOpt
      )
    }

  private def parseSeverity(filter: String): Option[LogSeverity] =
    filter.toLowerCase match
      case "error"   => Some(LogSeverity.Error)
      case "warning" => Some(LogSeverity.Warning)
      case "info"    => Some(LogSeverity.Info)
      case _         => None

  private def parseSeries(series: String): ScalaSeries =
    if series.isEmpty then ScalaSeries.All
    else scala.util.Try(ScalaSeries.valueOf(series)).getOrElse(ScalaSeries.All)

  /** Parse history params from request, with defaults */
  private def parseHistoryParams(projectName: String, params: Map[String, String]): Templates.HistoryParams =
    Templates.HistoryParams(
      projectName = projectName,
      series = params.get("series").flatMap(s => scala.util.Try(ScalaSeries.valueOf(s)).toOption).getOrElse(ScalaSeries.Next),
      excludeSnapshots = params.get("excludeSnapshots").contains("true"),
      excludeNightlies = params.get("excludeNightlies").contains("true")
    )

  def all(
      esClient: ElasticsearchClient,
      sqliteRepo: SqliteRepository,
      comparisonCache: ComparisonCache,
      historyCache: HistoryCache,
      buildsCache: BuildsCache,
      logsCache: LogsCache,
      projectsCache: ProjectsCache,
      failureStreaksCache: FailureStreaksCache,
      cacheManager: CacheManager,
      jwtSecret: String,
      basePath: String
  ): HttpRoutes[IO] =
    // Set the base path for URL generation in templates
    Templates.basePath = basePath

    val comparisonApi = ComparisonApi(esClient)
    val historyApi = HistoryApi(esClient)
    val logsApi = LogsApi(esClient)
    val notesApi = NotesApi(sqliteRepo)

    // API routes from Tapir endpoints (read-only)
    val apiRoutes = createApiRoutes(esClient, sqliteRepo, comparisonApi, historyApi, logsApi, notesApi)

    // API routes that require authentication (notes modification)
    val authApiRoutes = createAuthenticatedApiRoutes(notesApi, jwtSecret)

    // Swagger documentation (no logging for swagger static files)
    val swaggerRoutes = createSwaggerRoutes

    // Admin routes (requires admin team membership)
    val adminRoutes = createAdminRoutes(cacheManager, jwtSecret)

    // HTML page routes (with caching)
    val pageRoutes = createPageRoutes(
      esClient,
      sqliteRepo,
      comparisonApi,
      historyApi,
      logsApi,
      notesApi,
      comparisonCache,
      historyCache,
      buildsCache,
      logsCache,
      projectsCache,
      failureStreaksCache,
      jwtSecret
    )

    // Health check endpoint (no logging - used by k8s probes)
    val healthRoutes = HttpRoutes.of[IO]:
      case GET -> Root / "health" => Ok("ok")

    // Combine all routes with logging (except swagger and health which generate noise)
    healthRoutes <+> withLogging(apiRoutes <+> authApiRoutes <+> adminRoutes <+> pageRoutes) <+> swaggerRoutes

  private def createApiRoutes(
      esClient: ElasticsearchClient,
      sqliteRepo: SqliteRepository,
      comparisonApi: ComparisonApi,
      historyApi: HistoryApi,
      logsApi: LogsApi,
      notesApi: NotesApi
  ): HttpRoutes[IO] =
    val interpreter = Http4sServerInterpreter[IO]()

    val routes = List(
      // Comparison endpoints
      interpreter.toRoutes(Endpoints.compare.serverLogic(req => comparisonApi.compare(req))),
      interpreter.toRoutes(Endpoints.listScalaVersions.serverLogic(_ => esClient.listScalaVersions().map(Right(_)))),
      interpreter.toRoutes(Endpoints.listBuildIds.serverLogic(sv => esClient.listBuildIds(sv).map(Right(_)))),

      // History endpoints
      interpreter.toRoutes(Endpoints.projectHistory.serverLogic(name => historyApi.getHistory(name))),
      interpreter.toRoutes(Endpoints.buildDetails.serverLogic { case (name, buildId) =>
        historyApi.getBuildDetails(name, buildId)
      }),

      // Logs endpoints
      interpreter.toRoutes(Endpoints.logs.serverLogic { case (name, buildId, severity) =>
        logsApi.getLogs(name, buildId, severity)
      }),

      // Notes endpoints (read-only, modifications require auth - see createAuthenticatedApiRoutes)
      interpreter.toRoutes(Endpoints.getNotes.serverLogic(name => notesApi.getNotes(name))),

      // Similarity endpoint
      interpreter.toRoutes(Endpoints.similarFailures.serverLogic { case (name, buildId) =>
        ProjectName(name) match
          case Left(error)        => IO.pure(Left(error))
          case Right(projectName) =>
            for
              sigOpt <- sqliteRepo.getSignature(projectName, buildId)
              similar <- sigOpt match
                case Some(sig) => sqliteRepo.findSimilarFailures(sig.errorHash)
                case None      => IO.pure(Nil)
            yield Right(similar)
      }),

      // Latest builds endpoint
      interpreter.toRoutes(Endpoints.latestBuilds.serverLogic(_ => esClient.getLatestBuilds().map(Right(_))))
    )

    routes.reduce(_ <+> _)

  private def createAuthenticatedApiRoutes(
      notesApi: NotesApi,
      jwtSecret: String
  ): HttpRoutes[IO] =
    import io.circe.syntax.*
    import io.circe.generic.auto.*
    import org.http4s.circe.CirceEntityDecoder.*

    GitHubOAuth.authRequired(jwtSecret) { user =>
      HttpRoutes.of[IO] {
        // Add note - requires authentication
        case req @ POST -> Root / "api" / "v1" / "projects" / name / "notes" =>
          for
            request <- req.as[AddNoteRequest]
            result <- notesApi.addNote(name, request, user.login)
            response <- result match
              case Right(note) => Ok(note.asJson.noSpaces, `Content-Type`(MediaType.application.json))
              case Left(err)   => BadRequest(err)
          yield response

        // Update note - requires authentication
        case req @ PUT -> Root / "api" / "v1" / "projects" / name / "notes" / LongVar(noteId) =>
          for
            request <- req.as[UpdateNoteRequest]
            result <- notesApi.updateNote(name, noteId, request, user.login)
            response <- result match
              case Right(note) => Ok(note.asJson.noSpaces, `Content-Type`(MediaType.application.json))
              case Left(err)   => BadRequest(err)
          yield response

        // Delete note - requires authentication
        case DELETE -> Root / "api" / "v1" / "projects" / name / "notes" / LongVar(noteId) =>
          notesApi
            .deleteNote(name, noteId, user.login)
            .flatMap:
              case Right(_)  => NoContent()
              case Left(err) => BadRequest(err)
      }
    }

  private def createSwaggerRoutes: HttpRoutes[IO] =
    val swaggerEndpoints = SwaggerInterpreter()
      .fromEndpoints[IO](Endpoints.all, "Community Build Dashboard API", "1.0")

    Http4sServerInterpreter[IO]().toRoutes(swaggerEndpoints)

  private def createAdminRoutes(cacheManager: CacheManager, jwtSecret: String): HttpRoutes[IO] =
    import io.circe.syntax.*
    import io.circe.generic.auto.*

    GitHubOAuth.adminRequired(jwtSecret) { user =>
      HttpRoutes.of[IO] {
        // Clear all caches - requires admin authentication
        case POST -> Root / "admin" / "cache" / "clear" =>
          for
            _ <- log.info(s"Admin ${user.login}: Clearing all caches...")
            result <- cacheManager.clearAll()
            _ <- log.info(
              s"Admin ${user.login}: Cleared ${result.total} cache entries (comparisons: ${result.comparisons}, history: ${result.history}, builds: ${result.builds}, logs: ${result.logs})"
            )
            response <- Ok(result.asJson.noSpaces, `Content-Type`(MediaType.application.json))
          yield response

        // Get cache statistics - requires admin authentication
        case GET -> Root / "admin" / "cache" / "stats" =>
          for
            stats <- cacheManager.allStats
            response <- Ok(stats.asJson.noSpaces, `Content-Type`(MediaType.application.json))
          yield response
      }
    }

  private def createPageRoutes(
      esClient: ElasticsearchClient,
      sqliteRepo: SqliteRepository,
      comparisonApi: ComparisonApi,
      historyApi: HistoryApi,
      logsApi: LogsApi,
      notesApi: NotesApi,
      comparisonCache: ComparisonCache,
      historyCache: HistoryCache,
      buildsCache: BuildsCache,
      logsCache: LogsCache,
      projectsCache: ProjectsCache,
      failureStreaksCache: FailureStreaksCache,
      jwtSecret: String
  ): HttpRoutes[IO] =

    def getCachedComparison(request: CompareRequest): IO[Either[String, ComparisonResult]] =
      val cacheKey = ComparisonCache.Key.fromParams(
        request.baseScalaVersion,
        request.baseBuildId,
        request.targetScalaVersion,
        request.targetBuildId
      )
      if cacheKey.isValid then
        comparisonCache
          .getOrCompute(
            cacheKey,
            comparisonApi.compare(request).flatMap {
              case Right(result) => IO.pure(result)
              case Left(err)     => IO.raiseError(RuntimeException(err))
            }
          )
          .map(Right(_))
          .handleError(e => Left(e.getMessage))
      else comparisonApi.compare(request)

    def getCachedHistory(projectName: String): IO[Either[String, ProjectHistory]] =
      ProjectName(projectName) match
        case Left(err)   => IO.pure(Left(err))
        case Right(name) =>
          historyCache
            .getOrCompute(
              name,
              historyApi.getHistory(projectName).flatMap {
                case Right(history) => IO.pure(history)
                case Left(err)      => IO.raiseError(RuntimeException(err))
              }
            )
            .map(Right(_))
            .handleError(e => Left(e.getMessage))

    def getCachedBuilds(scalaVersion: Option[String], buildId: Option[String]): IO[List[BuildResult]] =
      val cacheKey = (scalaVersion, buildId) match
        case (Some(sv), _)  => BuildsCache.Key.ByScalaVersion(sv)
        case (_, Some(bid)) => BuildsCache.Key.ByBuildId(bid)
        case _              => BuildsCache.Key.Latest
      val compute = cacheKey match
        case BuildsCache.Key.ByScalaVersion(sv) => esClient.getBuildsByScalaVersion(sv)
        case BuildsCache.Key.ByBuildId(bid)     => esClient.getBuildsByBuildId(bid)
        case BuildsCache.Key.Latest             => esClient.getLatestBuilds()
      buildsCache.getOrCompute(cacheKey, compute)

    def getCachedLogs(projectName: String, buildId: String): IO[Either[String, ParsedLogs]] =
      val cacheKey = LogsCache.Key(projectName, buildId)
      logsCache
        .getOrCompute(
          cacheKey,
          logsApi.getLogs(projectName, buildId, None).flatMap {
            case Right(logs) => IO.pure(logs)
            case Left(err)   => IO.raiseError(RuntimeException(err))
          }
        )
        .map(Right(_))
        .handleError(e => Left(e.getMessage))

    def getCachedProjects(): IO[ProjectsList] =
      projectsCache.getOrCompute(esClient.listAllProjects())

    // Helper to compute failure streaks for a list of failing projects - CACHED
    def getCachedFailureStreaks(
        scalaVersion: Option[String],
        buildId: Option[String],
        failures: List[BuildResult]
    ): IO[Map[ProjectName, FailureStreakInfo]] =
      if failures.isEmpty then IO.pure(Map.empty)
      else
        val cacheKey = FailureStreaksCache.Key(scalaVersion, buildId)
        failureStreaksCache.getOrCompute(
          cacheKey,
          // Compute failure streaks in PARALLEL - expensive but cached
          failures
            .parTraverse: build =>
              esClient
                .getFailureStreakInfo(build.projectName)
                .map:
                  case Some(info) => Some(build.projectName -> info)
                  case None       => None
            .map(_.flatten.toMap)
        )

    HttpRoutes.of[IO] {
      // Home page - build results with version selector
      case req @ GET -> Root =>
        val params = req.params
        val scalaVersion = params.get("scalaVersion").filter(_.nonEmpty)
        val buildId = params.get("buildId").filter(_.nonEmpty)
        val series = parseSeries(params.get("series").getOrElse(""))
        val reason = params.get("reason").filter(_.nonEmpty)
        val sort = params
          .get("sort")
          .filter(_.nonEmpty)
          .flatMap(s => scala.util.Try(Templates.FailureSort.valueOf(s)).toOption)
          .getOrElse(Templates.FailureSort.Name)
        val sortAsc = params.get("sortAsc").forall(_ != "false") // Default true
        val isHtmx = req.headers.get(ci"HX-Request").isDefined
        val htmxTarget = req.headers.get(ci"HX-Target").map(_.head.value)

        for
          allVersions <- esClient.listScalaVersions()
          // When series is selected but no specific version, use latest version of that series
          effectiveScalaVersion = scalaVersion.orElse:
            Option
              .when(series != ScalaSeries.All):
                allVersions.find(v => ScalaSeries.fromScalaVersion(v) == series)
              .flatten
          homeParams = Templates.HomeParams(effectiveScalaVersion, buildId, series, reason, sort, sortAsc)
          // Load builds if: htmx request (user clicked something), or version/buildId selected
          // Skip only on initial full page load with no selection
          shouldLoadBuilds = isHtmx || effectiveScalaVersion.isDefined || buildId.isDefined
          buildIds <- if shouldLoadBuilds then esClient.listBuildIds(effectiveScalaVersion) else IO.pure(Nil)
          builds <- if shouldLoadBuilds then getCachedBuilds(effectiveScalaVersion, buildId) else IO.pure(Nil)
          // Only compute failure streaks when sorting by Streak (expensive but cached)
          failures = builds.filter(_.status == BuildStatus.Failure)
          failureStreaks <-
            if sort == Templates.FailureSort.Streak then
              getCachedFailureStreaks(effectiveScalaVersion, buildId, failures)
            else IO.pure(Map.empty[ProjectName, FailureStreakInfo])
          response <-
            if isHtmx then
              htmxTarget match
                case Some("home-content") =>
                  // Series changed - return full content (selector + results)
                  Ok(
                    Templates.homeContentPartial(builds, allVersions, buildIds, homeParams, failureStreaks),
                    `Content-Type`(MediaType.text.html)
                  )
                case _ =>
                  // Version/filter changed - return just results
                  Ok(
                    Templates.homeResultsPartial(builds, homeParams, failureStreaks),
                    `Content-Type`(MediaType.text.html)
                  )
            else
              // Full page request
              Ok(
                Templates.homePage(builds, allVersions, buildIds, homeParams, failureStreaks),
                `Content-Type`(MediaType.text.html)
              )
        yield response

      // Compare page
      case req @ GET -> Root / "compare" =>
        val params = req.params
        val request = CompareRequest(
          baseScalaVersion = params.get("baseScalaVersion").filter(_.nonEmpty),
          baseBuildId = params.get("baseBuildId").filter(_.nonEmpty),
          targetScalaVersion = params.get("targetScalaVersion").filter(_.nonEmpty),
          targetBuildId = params.get("targetBuildId").filter(_.nonEmpty)
        )

        val compareParams = Templates.CompareParams(
          baseScalaVersion = request.baseScalaVersion,
          baseBuildId = request.baseBuildId,
          targetScalaVersion = request.targetScalaVersion,
          targetBuildId = request.targetBuildId
        )

        val isHtmx = req.headers.get(ci"HX-Request").isDefined
        val hasComparison = request.targetScalaVersion.isDefined || request.targetBuildId.isDefined
        val isLoggedIn = GitHubOAuth.extractUser(jwtSecret, req).isDefined

        // Helper to get the actual buildId from comparison result (from diffs, not request params)
        def getActualBuildId(result: ComparisonResult): Option[String] =
          result.targetBuildId.orElse(
            result.newFailures.headOption.map(_.buildId)
              .orElse(result.newFixes.headOption.map(_.buildId))
              .orElse(result.stillFailing.headOption.map(_.buildId))
          )

        for
          versions <- esClient.listScalaVersions()
          buildIds <- esClient.listBuildIds(None)
          resultOpt <-
            if hasComparison
            then getCachedComparison(request).map(_.toOption)
            else IO.pure(None)
          // Batch fetch notes for the target buildId (avoids N+1 requests)
          notesMap <- resultOpt.flatMap(getActualBuildId) match
            case Some(buildId) => notesApi.getAllNotesByBuildId(buildId)
            case None          => IO.pure(Map.empty[String, List[ProjectNote]])
          response <-
            if isHtmx && hasComparison then
              // htmx request - return partial only
              resultOpt match
                case Some(result) =>
                  Ok(Templates.comparisonResultsPartial(result, compareParams, notesMap, isLoggedIn), `Content-Type`(MediaType.text.html))
                case None => Ok("No comparison results", `Content-Type`(MediaType.text.html))
            else
              // Full page request
              Ok(
                Templates.comparePage(versions, buildIds, resultOpt, compareParams, notesMap, isLoggedIn),
                `Content-Type`(MediaType.text.html)
              )
        yield response

      // Compare form partial (for htmx series filter) - returns form with filtered versions
      case req @ GET -> Root / "compare" / "form" =>
        val params = req.params
        val series = parseSeries(params.get("series").getOrElse(""))
        val compareParams = Templates.CompareParams(
          baseScalaVersion = None,
          baseBuildId = None,
          targetScalaVersion = None,
          targetBuildId = None,
          series = series
        )
        for
          versions <- esClient.listScalaVersions()
          buildIds <- esClient.listBuildIds(None)
          response <- Ok(
            Templates.compareFormPartial(versions, buildIds, compareParams),
            `Content-Type`(MediaType.text.html)
          )
        yield response

      // Compare filter partial (for htmx filter buttons) - uses cache
      case req @ GET -> Root / "compare" / "filter" =>
        val params = req.params
        val request = CompareRequest(
          baseScalaVersion = params.get("baseScalaVersion").filter(_.nonEmpty),
          baseBuildId = params.get("baseBuildId").filter(_.nonEmpty),
          targetScalaVersion = params.get("targetScalaVersion").filter(_.nonEmpty),
          targetBuildId = params.get("targetBuildId").filter(_.nonEmpty)
        )

        val filter = params.get("filter").filter(_.nonEmpty).getOrElse("all")
        val reason = params.get("reason").filter(_.nonEmpty)
        val isLoggedIn = GitHubOAuth.extractUser(jwtSecret, req).isDefined

        // Helper to get the actual buildId from comparison result
        def getActualBuildId(result: ComparisonResult): Option[String] =
          result.targetBuildId.orElse(
            result.newFailures.headOption.map(_.buildId)
              .orElse(result.newFixes.headOption.map(_.buildId))
              .orElse(result.stillFailing.headOption.map(_.buildId))
          )

        for
          result <- getCachedComparison(request)
          // Batch fetch notes for the target buildId
          notesMap <- result.toOption.flatMap(getActualBuildId) match
            case Some(buildId) => notesApi.getAllNotesByBuildId(buildId)
            case None          => IO.pure(Map.empty[String, List[ProjectNote]])
          response <- result match
            case Right(r) =>
              Ok(Templates.comparisonTablePartial(r, filter, reason, notesMap, isLoggedIn), `Content-Type`(MediaType.text.html))
            case Left(err) => BadRequest(err)
        yield response

      // Compare results partial (for htmx - full results including filters)
      case req @ GET -> Root / "compare" / "results" =>
        val params = req.params
        val request = CompareRequest(
          baseScalaVersion = params.get("baseScalaVersion").filter(_.nonEmpty),
          baseBuildId = params.get("baseBuildId").filter(_.nonEmpty),
          targetScalaVersion = params.get("targetScalaVersion").filter(_.nonEmpty),
          targetBuildId = params.get("targetBuildId").filter(_.nonEmpty)
        )

        val compareParams = Templates.CompareParams(
          baseScalaVersion = request.baseScalaVersion,
          baseBuildId = request.baseBuildId,
          targetScalaVersion = request.targetScalaVersion,
          targetBuildId = request.targetBuildId
        )

        val isLoggedIn = GitHubOAuth.extractUser(jwtSecret, req).isDefined

        // Helper to get the actual buildId from comparison result
        def getActualBuildId(result: ComparisonResult): Option[String] =
          result.targetBuildId.orElse(
            result.newFailures.headOption.map(_.buildId)
              .orElse(result.newFixes.headOption.map(_.buildId))
              .orElse(result.stillFailing.headOption.map(_.buildId))
          )

        for
          result <- getCachedComparison(request)
          // Batch fetch notes for the target buildId
          notesMap <- result.toOption.flatMap(getActualBuildId) match
            case Some(buildId) => notesApi.getAllNotesByBuildId(buildId)
            case None          => IO.pure(Map.empty[String, List[ProjectNote]])
          response <- result match
            case Right(r) =>
              Ok(Templates.comparisonResultsPartial(r, compareParams, notesMap, isLoggedIn), `Content-Type`(MediaType.text.html))
            case Left(err) => BadRequest(err)
        yield response

      // Projects list page - loads all projects at once
      case GET -> Root / "projects" =>
        for
          projectsList <- getCachedProjects()
          response <- Ok(Templates.projectsListPage(projectsList), `Content-Type`(MediaType.text.html))
        yield response

      // Project history page (org/repo format) - uses cache
      case req @ GET -> Root / "projects" / org / repo / "history" =>
        val projectName = s"$org/$repo"
        val historyParams = parseHistoryParams(projectName, req.params)
        val canEdit = GitHubOAuth.extractUser(jwtSecret, req).exists(_.isAdmin)

        for
          historyResult <- getCachedHistory(projectName)
          response <- historyResult match
            case Right(history) =>
              for
                // Fetch all notes for this project (project-level and build-level)
                allNotes <- sqliteRepo.getNotes(history.projectName)
                // Split into project-level and build-level notes
                projectNotes = allNotes.filter(_.buildId.isEmpty)
                buildNotesMap = allNotes.filter(_.buildId.isDefined).groupBy(_.buildId.get)
                resp <- Ok(
                  Templates.projectHistoryPage(history, projectNotes, buildNotesMap, historyParams, canEdit),
                  `Content-Type`(MediaType.text.html)
                )
              yield resp
            case Left(err) =>
              NotFound(err)
        yield response

      // Project history filter partial (for htmx) - uses cache
      case req @ GET -> Root / "projects" / org / repo / "history" / "filter" =>
        val projectName = s"$org/$repo"
        val historyParams = parseHistoryParams(projectName, req.params)
        val canEdit = GitHubOAuth.extractUser(jwtSecret, req).exists(_.isAdmin)

        for
          historyResult <- getCachedHistory(projectName)
          response <- historyResult match
            case Right(history) =>
              for
                allNotes <- sqliteRepo.getNotes(history.projectName)
                buildNotesMap = allNotes.filter(_.buildId.isDefined).groupBy(_.buildId.get)
                resp <- Ok(Templates.historyContentPartial(history, historyParams, buildNotesMap, canEdit), `Content-Type`(MediaType.text.html))
              yield resp
            case Left(err) =>
              NotFound(err)
        yield response

      // Project history infinite scroll - load more entries
      case req @ GET -> Root / "projects" / org / repo / "history" / "more" =>
        val projectName = s"$org/$repo"
        val historyParams = parseHistoryParams(projectName, req.params)
        val offset = req.params.get("offset").flatMap(_.toIntOption).getOrElse(0)
        val canEdit = GitHubOAuth.extractUser(jwtSecret, req).exists(_.isAdmin)

        for
          historyResult <- getCachedHistory(projectName)
          response <- historyResult match
            case Right(history) =>
              for
                allNotes <- sqliteRepo.getNotes(history.projectName)
                buildNotesMap = allNotes.filter(_.buildId.isDefined).groupBy(_.buildId.get)
                resp <- Ok(Templates.historyMoreEntries(history, historyParams, offset, buildNotesMap, canEdit), `Content-Type`(MediaType.text.html))
              yield resp
            case Left(err) =>
              NotFound(err)
        yield response

      // Note form (for htmx) - requires authentication
      case req @ GET -> Root / "projects" / org / repo / "notes" / "new" =>
        val projectName = s"$org/$repo"
        GitHubOAuth.extractUser(jwtSecret, req) match
          case Some(_) =>
            Ok(Components.noteForm(projectName).render, `Content-Type`(MediaType.text.html))
          case None =>
            Forbidden("<div class='text-red-600 p-2'>Please log in with GitHub to add notes</div>")

      // Note indicator (for htmx lazy loading in comparison table - shows icon + tooltip)
      case req @ GET -> Root / "projects" / org / repo / "notes" / "indicator" =>
        val projectName = s"$org/$repo"
        val params = req.params
        val buildId = params.get("buildId").filter(_.nonEmpty).getOrElse("")
        val indicatorId = params.get("indicatorId").filter(_.nonEmpty).getOrElse(s"note-ind-$org-$repo-${buildId.hashCode.abs}")
        val isLoggedIn = GitHubOAuth.extractUser(jwtSecret, req).isDefined
        for
          notesResult <- notesApi.getNotesByBuild(projectName, buildId)
          notes = notesResult.getOrElse(Nil)
          response <- Ok(
            Components.noteIndicator(projectName, buildId, indicatorId, notes, isLoggedIn).render,
            `Content-Type`(MediaType.text.html)
          )
        yield response

      // Notes cell content (for htmx lazy loading in comparison table - full notes column)
      case req @ GET -> Root / "projects" / org / repo / "builds" / buildId / "notes" / "cell" =>
        val projectName = s"$org/$repo"
        val params = req.params
        val cellId = params.get("cellId").filter(_.nonEmpty).getOrElse(s"notes-cell-$org-$repo-${buildId.hashCode.abs}")
        val canEdit = GitHubOAuth.extractUser(jwtSecret, req).exists(_.isAdmin)
        for
          notesResult <- notesApi.getNotesByBuild(projectName, buildId)
          notes = notesResult.getOrElse(Nil)
          response <- Ok(
            Components.notesCellContent(projectName, buildId, cellId, notes, canEdit).render,
            `Content-Type`(MediaType.text.html)
          )
        yield response

      // Delete note (htmx) - requires authentication (author or admin), returns refreshed cell
      case req @ DELETE -> Root / "projects" / org / repo / "notes" / LongVar(noteId) =>
        val projectName = s"$org/$repo"
        val params = req.params
        val cellId = params.get("cellId").filter(_.nonEmpty).getOrElse(s"notes-cell-$org-$repo")
        val buildId = params.get("buildId").filter(_.nonEmpty).getOrElse("")
        GitHubOAuth.extractUser(jwtSecret, req) match
          case Some(user) =>
            for
              deleteResult <- notesApi.deleteNote(projectName, noteId, user.login, canEdit = user.isAdmin)
              response <- deleteResult match
                case Right(_) =>
                  for
                    notesResult <- notesApi.getNotesByBuild(projectName, buildId)
                    notes = notesResult.getOrElse(Nil)
                    resp <- Ok(
                      Components.notesCellContent(projectName, buildId, cellId, notes, canEdit = user.isAdmin).render,
                      `Content-Type`(MediaType.text.html)
                    )
                  yield resp
                case Left(err) =>
                  BadRequest(s"<div class='text-red-600 text-xs'>$err</div>")
            yield response
          case None =>
            Forbidden("<div class='text-red-600 text-xs'>Please log in first</div>")

      // Note form for inline/build-specific notes (for htmx) - requires authentication
      case req @ GET -> Root / "projects" / org / repo / "notes" / "form" =>
        val projectName = s"$org/$repo"
        val params = req.params
        val buildId = params.get("buildId").filter(_.nonEmpty)
        val formId = params.get("formId").filter(_.nonEmpty).getOrElse(
          s"note-form-$org-$repo-${buildId.map(_.hashCode.abs).getOrElse(0)}"
        )
        GitHubOAuth.extractUser(jwtSecret, req) match
          case Some(_) =>
            buildId match
              case Some(bid) =>
                Ok(Components.noteFormInline(projectName, bid, formId).render, `Content-Type`(MediaType.text.html))
              case None =>
                Ok(Components.noteForm(projectName).render, `Content-Type`(MediaType.text.html))
          case None =>
            Forbidden("<div class='text-red-600 text-xs p-1'>Please log in first</div>")

      // Cancel note form (for htmx) - returns to notes cell state
      case req @ GET -> Root / "projects" / org / repo / "notes" / "cancel" =>
        val projectName = s"$org/$repo"
        val params = req.params
        val buildId = params.get("buildId").filter(_.nonEmpty)
        val formId = params.get("formId").filter(_.nonEmpty).getOrElse(
          s"note-form-$org-$repo-${buildId.map(_.hashCode.abs).getOrElse(0)}"
        )
        Ok(Components.noteFormCancelled(projectName, buildId, formId).render, `Content-Type`(MediaType.text.html))

      // Create note (htmx form submission) - requires authentication
      case req @ POST -> Root / "projects" / org / repo / "notes" =>
        val projectName = s"$org/$repo"
        GitHubOAuth.extractUser(jwtSecret, req) match
          case None =>
            Forbidden("<div class='text-red-600 p-2'>Please log in with GitHub to add notes</div>")
          case Some(user) =>
            for
              formData <- req.as[UrlForm]
              noteText = formData.getFirst("note").getOrElse("")
              githubIssueUrl = formData.getFirst("githubIssueUrl").filter(_.nonEmpty)
              buildId = formData.getFirst("buildId").filter(_.nonEmpty)
              formId = formData.getFirst("formId").filter(_.nonEmpty).getOrElse(
                s"note-form-${org}-${repo}-${buildId.map(_.hashCode.abs).getOrElse(0)}"
              )
              response <-
                if noteText.isEmpty then BadRequest("<div class='text-red-600 p-2'>Note text is required</div>")
                else
                  val request = AddNoteRequest(None, buildId, noteText, githubIssueUrl)
                  notesApi
                    .addNote(projectName, request, user.login)
                    .flatMap:
                      case Right(note) =>
                        // For inline forms, show success message; for regular forms, show the note card
                        if buildId.isDefined then
                          Ok(Components.noteAddedInline(projectName, buildId.get, formId).render, `Content-Type`(MediaType.text.html))
                        else
                          Ok(Components.noteCard(note).render, `Content-Type`(MediaType.text.html))
                      case Left(err) =>
                        BadRequest(s"<div class='text-red-600 p-2'>$err</div>")
            yield response

      // Delete project-level note (htmx) - requires edit access (admin)
      case req @ DELETE -> Root / "projects" / org / repo / "notes" / LongVar(noteId) / "delete" =>
        val projectName = s"$org/$repo"
        GitHubOAuth.extractUser(jwtSecret, req) match
          case Some(user) if user.isAdmin =>
            for
              deleteResult <- notesApi.deleteNote(projectName, noteId, user.login, canEdit = true)
              response <- deleteResult match
                case Right(_) =>
                  // Return empty fragment to remove the note card
                  Ok("", `Content-Type`(MediaType.text.html))
                case Left(err) =>
                  BadRequest(s"<div class='text-red-600 text-sm p-2'>$err</div>")
            yield response
          case Some(_) =>
            Forbidden("<div class='text-red-600 text-sm p-2'>Admin access required</div>")
          case None =>
            Forbidden("<div class='text-red-600 text-sm p-2'>Please log in first</div>")

      // Build logs page (org/repo format) - cached
      // Uses custom extractor to handle buildId containing slashes
      case req @ GET -> ProjectBuildLogs(org, repo, buildId) =>
        val projectName = s"$org/$repo"
        val severityFilter = req.params.get("severity").filter(_ != "all")
        for
          logsResult <- getCachedLogs(projectName, buildId)
          response <- logsResult match
            case Right(logs) =>
              // Apply severity filter after caching (cache stores full logs)
              val filtered =
                severityFilter.flatMap(parseSeverity).map(s => ParsedLogs.filterBySeverity(logs, s)).getOrElse(logs)
              // Check if this is an htmx request for partial update
              val isHtmx = req.headers.get(ci"HX-Request").isDefined
              if isHtmx then Ok(Templates.logsPartial(filtered), `Content-Type`(MediaType.text.html))
              else Ok(Templates.logsPage(projectName, buildId, filtered), `Content-Type`(MediaType.text.html))
            case Left(err) =>
              // Show a proper error page instead of plain text
              Ok(Templates.logsErrorPage(projectName, buildId, err), `Content-Type`(MediaType.text.html))
        yield response

      // API docs redirect
      case GET -> Root / "docs" =>
        val docsPath = if Templates.basePath.isEmpty then "/docs/index.html" else s"${Templates.basePath}/docs/index.html"
        PermanentRedirect(headers.Location(Uri.unsafeFromString(docsPath)))
    }

/** Custom path extractor for build logs URLs with buildId containing slashes.
  *
  * Build IDs can contain `/` characters (e.g., `dotty:lts/varhandle-lazy-vals:2025-12-29`). When URL-encoded as `%2F`,
  * http4s decodes these before path matching, causing standard route patterns to fail because there are too many path
  * segments.
  *
  * This extractor handles paths like: `/projects/org/repo/builds/ANYTHING_WITH_SLASHES/logs`
  */
object ProjectBuildLogs:
  def unapply(path: Uri.Path): Option[(String, String, String)] =
    val segments = path.segments.map(_.decoded())
    segments.toList match
      case "projects" :: org :: repo :: "builds" :: rest if rest.lastOption.contains("logs") =>
        val buildId = rest.init.mkString("/")
        if buildId.nonEmpty then Some((org, repo, buildId))
        else None
      case _ => None
