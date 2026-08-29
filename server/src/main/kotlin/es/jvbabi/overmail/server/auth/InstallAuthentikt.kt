package es.jvbabi.overmail.server.auth

import es.jvbabi.authentikt.core.installAuthentikt
import es.jvbabi.authentikt.core.step.plugins.builtin.DonePlugin
import es.jvbabi.authentikt.core.step.plugins.builtin.EmailUserSelectionPlugin
import es.jvbabi.overmail.server.config.ApplicationConfig
import es.jvbabi.overmail.server.config.SmtpConfig
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.database.models.Users
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import kotlin.time.Duration.Companion.days

/** Mounted below the `/api` prefix Caddy forwards, so the flow routes end up under `/api/auth`. */
const val AUTH_API_PREFIX = "/api/auth"

private val SESSION_VALIDITY = 30.days

/**
 * Two steps: identify the account by username or email, then prove control of its mailbox with a
 * one-time code. There is no password anywhere in this flow, and no account is ever created —
 * an unknown identifier is simply rejected.
 */
fun Application.installOvermailAuthentikt() {
    // Resolved eagerly because none of these touch the database; the database itself is pulled
    // inside the suspending callback instead, so starting up does not block on schema creation.
    val config: ApplicationConfig by dependencies
    val jwtService: JwtService by dependencies
    val smtpConfig: SmtpConfig by dependencies

    val identifierPlugin = EmailUserSelectionPlugin<User> {
        findUserByEmail { identifier ->
            // Sign-in accepts either the username or the email, so both are matched.
            dependencies.resolve<OvermailDatabase>()
                .query { User.find { (Users.username eq identifier) or (Users.email eq identifier) }.firstOrNull() }
                ?.let(::OvermailAuthentiktUser)
        }
        withUsername = true
    }

    val verificationPlugin = EmailVerificationPlugin(smtpConfig)

    val donePlugin = DonePlugin<User> {
        onSuccess { _, user ->
            cookie(
                Cookie(
                    name = SESSION_COOKIE_NAME,
                    // A JWT is already URL safe; the default encoding would only add Ktor's
                    // $x-enc marker to the Set-Cookie header.
                    encoding = CookieEncoding.RAW,
                    value = jwtService.issue(user.id.value, SESSION_VALIDITY),
                    path = "/",
                    maxAge = SESSION_VALIDITY.inWholeSeconds.toInt(),
                    // Not readable from JavaScript, and only sent back over the proxied https origin.
                    httpOnly = true,
                    secure = true,
                    extensions = mapOf("SameSite" to "Lax"),
                )
            )
        }
    }

    val instance = installAuthentikt {
        apiPrefix = AUTH_API_PREFIX
        baseUrl = config.baseUrl
        uiLoginBaseUrl = URLBuilder(config.baseUrl).appendPathSegments("auth").buildString()

        install(identifierPlugin)
        install(verificationPlugin)
        install(donePlugin)

        authorization { session ->
            when {
                session.identifiedUser == null -> identifierPlugin
                !session.has(verificationPlugin) -> verificationPlugin
                else -> donePlugin
            }
        }
    }

    routing {
        route(AUTH_API_PREFIX) {
            /**
             * Starts a sign-in flow and hands back its id. Everything after this happens under
             * /api/auth/authentikt/flow/{session_id}.
             */
            post("/login") {
                call.respond(LoginResponse(instance.createNewSession().sessionId))
            }

            authenticate {
                /**
                 * Who the session cookie belongs to, or 401. This is what the frontend asks
                 * before rendering anything.
                 */
                get("/session") {
                    val user = call.user
                    call.respond(SessionResponse(user.id.value.toString(), user.username, user.email))
                }
            }

            /** Drops the cookie. The flow sessions themselves are short lived anyway. */
            post("/logout") {
                call.response.cookies.append(
                    Cookie(
                        name = SESSION_COOKIE_NAME,
                    // A JWT is already URL safe; the default encoding would only add Ktor's
                    // $x-enc marker to the Set-Cookie header.
                    encoding = CookieEncoding.RAW,
                        value = "",
                        path = "/",
                        maxAge = 0,
                        httpOnly = true,
                        secure = true,
                        extensions = mapOf("SameSite" to "Lax"),
                    )
                )
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

@Serializable
private data class LoginResponse(
    @SerialName("session_id") val sessionId: String,
)

@Serializable
private data class SessionResponse(
    @SerialName("user_id") val userId: String,
    @SerialName("username") val username: String,
    @SerialName("email") val email: String,
)
