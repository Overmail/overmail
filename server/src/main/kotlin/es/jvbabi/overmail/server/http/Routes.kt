package es.jvbabi.overmail.server.http

import es.jvbabi.overmail.server.auth.SESSION_AUTH
import es.jvbabi.overmail.server.domain.models.User
import es.jvbabi.overmail.server.http.ai.aiProcessing
import es.jvbabi.overmail.server.http.avatars.avatars
import es.jvbabi.overmail.server.http.mails.mails
import es.jvbabi.overmail.server.http.threads.threads
import es.jvbabi.overmail.server.http.webapp.agent.agentProcess
import es.jvbabi.overmail.server.http.webapp.home.emailGraph
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.OpenApiInfo
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.OpenApiDocSource
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

internal fun Application.configureRouting() {
    routing {
        // Caddy forwards /api* unchanged and sends everything else to SvelteKit, so every route
        // this server owns has to live under /api.
        route("/api") {
            // Reads the live routing tree, so every route below shows up without a checked-in spec.
            swaggerUI("/swagger") {
                info = OpenApiInfo(title = "Overmail", version = "1.0")
                source = OpenApiDocSource.Routing(ContentType.Application.Json)
                // Default is documentation.yaml, but the source above emits JSON.
                remotePath = "documentation.json"
            }

            /**
             * Reports whether the server is up.
             */
            get("/health") {
                call.respondText("ok")
            }

            authenticate(SESSION_AUTH) {
                /**
                 * Reports who the session token belongs to.
                 */
                get("/test") {
                    // Inside `authenticate` there is a user, or the request never got here.
                    val user = call.principal<User>() ?: return@get call.respond(HttpStatusCode.Unauthorized)

                    call.respond(
                        SignedInUser(
                            id = user.id.toString(),
                            username = user.username,
                            email = user.email,
                        )
                    )
                }
            }

            // The mailbox itself, cut for no screen in particular.
            route("/mails") { mails() }

            // The threads themselves, as the skeleton a grouped list is laid out from.
            route("/threads") { threads() }

            // Operating the mail agent, not a domain object of its own.
            route("/ai") { aiProcessing() }

            // The pictures of the people in the mailbox, and filling that cache up.
            route("/avatars") { avatars() }

            // Routes that exist for a screen of the web app rather than for a domain object, cut
            // the way that screen needs them.
            route("/webapp/home") {
                route("/email-graph") { emailGraph() }
            }

            route("/webapp/agent") {
                // A socket rather than a poll: the agent goes from mail to mail on its own clock,
                // and the display is meant to follow it.
                route("/process") { agentProcess() }
            }
        }
    }
}

/** Who the caller is, as `/api/test` reports it. */
@Serializable
data class SignedInUser(
    val id: String,
    val username: String,
    val email: String,
)
