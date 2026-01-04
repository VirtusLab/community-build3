package dashboard.auth

import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.{Location, `WWW-Authenticate`, `Content-Type`}
import org.http4s.client.Client
import org.http4s.circe.*
import org.http4s.circe.CirceEntityDecoder.*
import io.circe.{Decoder, Json}
import io.circe.parser.parse as parseJson
import pdi.jwt.{JwtCirce, JwtAlgorithm, JwtClaim}

import java.time.Instant
import scala.concurrent.duration.*

/** GitHub OAuth authentication */
object GitHubOAuth:

  /** Teams that grant admin access */
  private val AdminTeams: List[(String, String)] = List(
    "VirtusLab" -> "scala-open-source",
    "scala" -> "release-officers"
  )

  /** Fallback: usernames that always have admin access (when team check fails due to org permissions) */
  private val AdminUsers: Set[String] = Set(
    "WojciechMazur"
    // Add more usernames as needed
  )

  final case class Config(
      clientId: String,
      clientSecret: String,
      callbackUrl: String,
      jwtSecret: String
  )

  /** Authenticated user info */
  final case class User(
      login: String,
      name: Option[String],
      avatarUrl: String,
      isAdmin: Boolean = false
  )

  /** Create auth routes */
  def routes(config: Config, httpClient: Client[IO]): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      // Redirect to GitHub OAuth
      case GET -> Root / "auth" / "github" =>
        val authUrl = Uri
          .unsafeFromString("https://github.com/login/oauth/authorize")
          .withQueryParam("client_id", config.clientId)
          .withQueryParam("redirect_uri", config.callbackUrl)
          .withQueryParam("scope", "read:user read:org")

        Found(Location(authUrl))

      // OAuth callback
      case req @ GET -> Root / "auth" / "callback" =>
        req.params.get("code") match
          case None =>
            BadRequest("Missing authorization code")
          case Some(code) =>
            val isSecure = config.callbackUrl.startsWith("https://")
            val result = for
              tokenResponse <- exchangeCodeForToken(httpClient, config, code)
              user <- fetchUserInfo(httpClient, tokenResponse.accessToken)
              isAdmin <- checkAdminTeamMembership(httpClient, tokenResponse.accessToken, user.login)
              userWithAdmin = user.copy(isAdmin = isAdmin)
              jwt = createJwt(config.jwtSecret, userWithAdmin)
              response <- Found(Location(Uri.unsafeFromString("/")))
                .map(
                  _.addCookie(
                    ResponseCookie(
                      name = "auth_token",
                      content = jwt,
                      httpOnly = true,
                      secure = isSecure,
                      maxAge = Some(7.days.toSeconds),
                      path = Some("/")
                    )
                  )
                )
            yield response
            result.handleErrorWith { error =>
              scribe.error(s"OAuth callback failed: ${error.getMessage}", error)
              InternalServerError(s"Authentication failed: ${error.getMessage}")
            }

      // Logout
      case POST -> Root / "auth" / "logout" =>
        Found(Location(Uri.unsafeFromString("/")))
          .map(
            _.addCookie(
              ResponseCookie(
                name = "auth_token",
                content = "",
                httpOnly = true,
                maxAge = Some(0),
                path = Some("/")
              )
            )
          )

      // Get current user (JSON)
      case req @ GET -> Root / "auth" / "me" =>
        extractUser(config.jwtSecret, req) match
          case Some(user) =>
            import io.circe.syntax.*
            Ok(user.asJson(using userEncoder).noSpaces)
          case None =>
            Unauthorized(`WWW-Authenticate`(Challenge("Bearer", "dashboard")))

      // Auth status (HTML fragment for htmx)
      case req @ GET -> Root / "auth" / "status" =>
        extractUser(config.jwtSecret, req) match
          case Some(user) =>
            val adminBadge =
              if user.isAdmin then
                """<span class="ml-2 px-2 py-0.5 text-xs bg-purple-100 text-purple-700 rounded">admin</span>"""
              else ""
            Ok(
              s"""
              <div class="flex items-center space-x-3">
                <img src="${user.avatarUrl}" alt="${user.login}" class="w-8 h-8 rounded-full" />
                <span class="text-gray-700 font-medium">${user.login}$adminBadge</span>
                <form action="/auth/logout" method="POST" class="inline">
                  <button type="submit" class="text-gray-500 hover:text-gray-700 text-sm">Logout</button>
                </form>
              </div>
            """,
              `Content-Type`(MediaType.text.html)
            )
          case None =>
            Ok(
              """<a href="/auth/github" class="text-gray-600 hover:text-gray-900">Sign in with GitHub</a>""",
              `Content-Type`(MediaType.text.html)
            )
    }

  /** Token response from GitHub */
  private case class TokenResponse(accessToken: String)

  private given Decoder[TokenResponse] = Decoder.instance: c =>
    c.downField("access_token").as[String].map(TokenResponse(_))

  private def exchangeCodeForToken(
      client: Client[IO],
      config: Config,
      code: String
  ): IO[TokenResponse] =
    val request = Request[IO](
      method = Method.POST,
      uri = Uri.unsafeFromString("https://github.com/login/oauth/access_token")
    ).withEntity(
      UrlForm(
        "client_id" -> config.clientId,
        "client_secret" -> config.clientSecret,
        "code" -> code
      )
    ).putHeaders(
      org.http4s.headers.Accept(MediaType.application.json)
    )

    client.expect[TokenResponse](request)

  private def fetchUserInfo(client: Client[IO], accessToken: String): IO[User] =
    val request = Request[IO](
      method = Method.GET,
      uri = Uri.unsafeFromString("https://api.github.com/user")
    ).putHeaders(
      org.http4s.headers.Authorization(Credentials.Token(AuthScheme.Bearer, accessToken))
    )

    client
      .expect[Json](request)
      .map: json =>
        val cursor = json.hcursor
        User(
          login = cursor.downField("login").as[String].getOrElse("unknown"),
          name = cursor.downField("name").as[String].toOption,
          avatarUrl = cursor.downField("avatar_url").as[String].getOrElse("")
        )

  /** Check if user is a member of any admin team by fetching their teams */
  private def checkAdminTeamMembership(
      client: Client[IO],
      accessToken: String,
      username: String
  ): IO[Boolean] =
    // Fetch all teams the user belongs to (paginated, get first 100)
    val request = Request[IO](
      method = Method.GET,
      uri = Uri.unsafeFromString("https://api.github.com/user/teams?per_page=100")
    ).putHeaders(
      org.http4s.headers.Authorization(Credentials.Token(AuthScheme.Bearer, accessToken))
    )

    client
      .expect[Json](request)
      .map { json =>
        val userTeams = json.asArray.toList.flatten.flatMap { team =>
          val orgLogin = team.hcursor.downField("organization").downField("login").as[String].toOption
          val teamSlug = team.hcursor.downField("slug").as[String].toOption
          (orgLogin, teamSlug) match
            case (Some(org), Some(slug)) => Some(s"$org/$slug")
            case _                       => None
        }
        scribe.info(s"User $username belongs to teams: ${userTeams.mkString(", ")}")
        scribe.info(s"Admin teams configured: ${AdminTeams.map { case (o, t) => s"$o/$t" }.mkString(", ")}")

        val isTeamAdmin = userTeams.exists { userTeam =>
          AdminTeams.exists { case (adminOrg, adminTeam) =>
            userTeam.equalsIgnoreCase(s"$adminOrg/$adminTeam")
          }
        }
        val isUserAdmin = AdminUsers.contains(username)
        val isAdmin = isTeamAdmin || isUserAdmin
        scribe.info(s"User $username isAdmin: $isAdmin (team: $isTeamAdmin, user: $isUserAdmin)")
        isAdmin
      }
      .handleErrorWith { error =>
        scribe.error(s"Failed to check team membership for $username: ${error.getMessage}", error)
        IO.pure(false)
      }

  /** Create a JWT for the authenticated user */
  private def createJwt(secret: String, user: User): String =
    val claim = JwtClaim(
      subject = Some(user.login),
      issuedAt = Some(Instant.now().getEpochSecond),
      expiration = Some(Instant.now().plusSeconds(7.days.toSeconds).getEpochSecond)
    ) + ("name", user.name.getOrElse("")) + ("avatar_url", user.avatarUrl) + ("is_admin", user.isAdmin)

    JwtCirce.encode(claim, secret, JwtAlgorithm.HS256)

  /** Extract user from request cookies */
  def extractUser(jwtSecret: String, request: Request[IO]): Option[User] =
    for
      cookie <- request.cookies.find(_.name == "auth_token")
      claim <- JwtCirce.decode(cookie.content, jwtSecret, Seq(JwtAlgorithm.HS256)).toOption
      login <- claim.subject
      contentJson <- parseJson(claim.content).toOption
    yield User(
      login = login,
      name = contentJson.hcursor.downField("name").as[String].toOption.filter(_.nonEmpty),
      avatarUrl = contentJson.hcursor.downField("avatar_url").as[String].getOrElse(""),
      isAdmin = contentJson.hcursor.downField("is_admin").as[Boolean].getOrElse(false)
    )

  /** Middleware to require authentication - only applies to routes that match */
  def authRequired(jwtSecret: String)(mkRoutes: User => HttpRoutes[IO]): HttpRoutes[IO] =
    cats.data.Kleisli { (request: Request[IO]) =>
      extractUser(jwtSecret, request) match
        case Some(user) =>
          // User authenticated - run the routes
          mkRoutes(user).run(request)
        case None =>
          // Not authenticated - check if route would match with a dummy user
          val dummyUser = User("", None, "", false)
          mkRoutes(dummyUser).run(request).semiflatMap { _ =>
            // Route matched but user not authenticated
            Unauthorized(`WWW-Authenticate`(Challenge("Bearer", "dashboard")))
          }
    }

  /** Middleware to require admin access - only applies to routes that match */
  def adminRequired(jwtSecret: String)(mkRoutes: User => HttpRoutes[IO]): HttpRoutes[IO] =
    cats.data.Kleisli { (request: Request[IO]) =>
      extractUser(jwtSecret, request) match
        case Some(user) if user.isAdmin =>
          // Admin user - run the routes
          mkRoutes(user).run(request)
        case Some(_) =>
          // Authenticated but not admin - check if route matches
          val dummyUser = User("", None, "", true)
          mkRoutes(dummyUser).run(request).semiflatMap { _ =>
            // Route matched but user not admin
            Forbidden("Admin access required")
          }
        case None =>
          // Not authenticated - check if route would match
          val dummyUser = User("", None, "", true)
          mkRoutes(dummyUser).run(request).semiflatMap { _ =>
            // Route matched but user not authenticated
            Unauthorized(`WWW-Authenticate`(Challenge("Bearer", "dashboard")))
          }
    }

  private given userEncoder: io.circe.Encoder[User] = io.circe.Encoder.instance: u =>
    io.circe.Json.obj(
      "login" -> io.circe.Json.fromString(u.login),
      "name" -> u.name.fold(io.circe.Json.Null)(io.circe.Json.fromString),
      "avatar_url" -> io.circe.Json.fromString(u.avatarUrl),
      "is_admin" -> io.circe.Json.fromBoolean(u.isAdmin)
    )
