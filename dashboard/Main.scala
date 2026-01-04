package dashboard

import cats.effect.{IO, IOApp, Resource}
import cats.syntax.all.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.server.Server
import org.http4s.client.Client
import com.comcast.ip4s.*
import scribe.cats.{io => log}
import fs2.io.net.tls.TLSContext

import dashboard.web.Routes
import dashboard.data.{
  BuildsCache,
  CacheManager,
  ComparisonCache,
  FailureStreaksCache,
  HistoryCache,
  LogsCache,
  ProjectsCache,
  ElasticsearchClient,
  SqliteRepository
}
import dashboard.auth.GitHubOAuth

object Main extends IOApp.Simple:

  private val config = AppConfig.default

  override def run: IO[Unit] =
    log.info("Starting Community Build Dashboard...") *>
      serverResource
        .use: _ =>
          log.info(s"Server started at http://${config.host}:${config.port}") *>
            log.info(s"Swagger UI at http://${config.host}:${config.port}/docs") *>
            IO.never
        .handleErrorWith: error =>
          log.error(s"Failed to start: ${error.getMessage}") *>
            IO.raiseError(error)

  private def serverResource: Resource[IO, Server] =
    for
      // Create HTTP client with optional insecure TLS for internal k8s services with self-signed certs
      httpClient <- createHttpClient(config.elasticsearch.tlsInsecure)
      esClient <- ElasticsearchClient.resource(config.elasticsearch, httpClient)
      _ <- Resource.eval(validateInfrastructure(esClient))
      sqliteRepo <- SqliteRepository.resource(config.sqlite)
      _ <- Resource.eval(sqliteRepo.validateConnection())
      comparisonCache <- Resource.eval(ComparisonCache.inMemory())
      historyCache <- Resource.eval(HistoryCache.inMemory())
      buildsCache <- Resource.eval(BuildsCache.inMemory())
      logsCache <- Resource.eval(LogsCache.inMemory())
      projectsCache <- Resource.eval(ProjectsCache.inMemory())
      failureStreaksCache <- Resource.eval(FailureStreaksCache.inMemory())
      cacheManager = CacheManager(comparisonCache, historyCache, buildsCache, logsCache, projectsCache, failureStreaksCache)
      _ <- Resource.eval(log.info("Caches initialized"))
      routes = buildRoutes(
        httpClient,
        esClient,
        sqliteRepo,
        comparisonCache,
        historyCache,
        buildsCache,
        logsCache,
        projectsCache,
        failureStreaksCache,
        cacheManager
      )
      server <- EmberServerBuilder
        .default[IO]
        .withHost(config.host)
        .withPort(config.port)
        .withMaxHeaderSize(16384) // 16KB for JWT cookies
        .withHttpApp(routes.orNotFound)
        .build
    yield server

  private def createHttpClient(tlsInsecure: Boolean): Resource[IO, Client[IO]] =
    if tlsInsecure then
      // Create insecure TLS context that accepts self-signed certificates
      for
        tlsContext <- Resource.eval(IO {
          import javax.net.ssl.*
          import java.security.cert.X509Certificate
          val trustAllCerts = Array[TrustManager](new X509TrustManager {
            override def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit = ()
            override def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit = ()
            override def getAcceptedIssuers: Array[X509Certificate] = Array.empty
          })
          val sslContext = SSLContext.getInstance("TLS")
          sslContext.init(null, trustAllCerts, new java.security.SecureRandom())
          TLSContext.Builder.forAsync[IO].fromSSLContext(sslContext)
        })
        client <- EmberClientBuilder
          .default[IO]
          .withMaxResponseHeaderSize(16384)
          .withTLSContext(tlsContext)
          .build
      yield client
    else EmberClientBuilder.default[IO].withMaxResponseHeaderSize(16384).build

  private def validateInfrastructure(esClient: ElasticsearchClient): IO[Unit] =
    log.info("Validating infrastructure connectivity...") *>
      esClient
        .validateConnection()
        .handleErrorWith: error =>
          IO.raiseError(RuntimeException(s"Elasticsearch validation failed: ${error.getMessage}", error))

  private def buildRoutes(
      httpClient: Client[IO],
      esClient: ElasticsearchClient,
      sqliteRepo: SqliteRepository,
      comparisonCache: ComparisonCache,
      historyCache: HistoryCache,
      buildsCache: BuildsCache,
      logsCache: LogsCache,
      projectsCache: ProjectsCache,
      failureStreaksCache: FailureStreaksCache,
      cacheManager: CacheManager
  ): org.http4s.HttpRoutes[IO] =
    val jwtSecret = config.github.jwtSecret
    val basePath = config.basePath
    val mainRoutes = Routes.all(
      esClient,
      sqliteRepo,
      comparisonCache,
      historyCache,
      buildsCache,
      logsCache,
      projectsCache,
      failureStreaksCache,
      cacheManager,
      jwtSecret,
      basePath
    )
    val authRoutes = GitHubOAuth.routes(config.github, httpClient)
    authRoutes <+> mainRoutes

final case class AppConfig(
    host: Host,
    port: Port,
    basePath: String, // URL path prefix (e.g., "/dashboard" when behind ingress)
    elasticsearch: ElasticsearchConfig,
    sqlite: SqliteConfig,
    github: GitHubOAuth.Config
)

final case class ElasticsearchConfig(
    host: String,
    port: Int,
    pathPrefix: Option[String],
    username: String,
    password: String,
    tlsInsecure: Boolean // Skip TLS verification (for internal k8s with self-signed certs)
)

final case class SqliteConfig(
    path: String
)

object AppConfig:

  def default: AppConfig =
    scribe.info("Loading configuration from environment...")
    AppConfig(
      host = host"0.0.0.0",
      port = port"8080",
      basePath = Env("BASE_PATH", "").stripSuffix("/"), // Remove trailing slash for consistency
      elasticsearch = ElasticsearchConfig(
        host = Env("ES_HOST", "community-build-es-http"),
        port = Env("ES_PORT", 9200),
        pathPrefix = Env.opt("ES_PATH_PREFIX"),
        username = Env("ES_USERNAME", "elastic", secret = true),
        password = Env("ES_PASSWORD", "changeme", secret = true),
        tlsInsecure = Env("ES_TLS_INSECURE", false)
      ),
      sqlite = SqliteConfig(
        path = Env("SQLITE_PATH", "dashboard.db")
      ),
      github = GitHubOAuth.Config(
        clientId = Env("GITHUB_CLIENT_ID", "", secret = true),
        clientSecret = Env("GITHUB_CLIENT_SECRET", "", secret = true),
        callbackUrl = Env("GITHUB_CALLBACK_URL", "http://localhost:8080/auth/callback"),
        jwtSecret = Env("JWT_SECRET", "change-me-in-production", secret = true)
      )
    )

object Env:
  private def renderValue(value: String, secret: Boolean): String =
    if secret then "*".repeat(value.length) else value

  private def getEnv[T: FromString as transform](name: String, secret: Boolean): Option[T] = {
    sys.env
      .get(name)
      .flatMap: stringValue =>
        summon[FromString[T]]
          .fromString(stringValue)
          .orElse:
            scribe.warn(s"Config: $name = ${renderValue(stringValue, secret)} is invalid, cannot be parsed")
            None
      .map: value =>
        scribe.info(s"Config: $name = ${renderValue(value.toString, secret)}")
        value
  }

  /** Get env variable or default, logging when default is used */
  def opt[T: FromString](name: String, secret: Boolean = false): Option[T] =
    getEnv(name, secret).orElse:
      scribe.info(s"Config: $name not defined")
      None

    // @targetName("envFromString")
  def apply[T: FromString as transform](name: String, default: T, secret: Boolean = false): T =
    getEnv(name, secret)
      .getOrElse:
        scribe.info(s"Config: $name not defined, using default: ${renderValue(default.toString, secret)}")
        default

  trait FromString[T]:
    def fromString(s: String): Option[T]

  given FromString[String] = Some(_)
  given FromString[Int] = _.toIntOption
  given FromString[Boolean] = _.toBooleanOption
