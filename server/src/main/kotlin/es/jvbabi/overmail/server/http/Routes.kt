package es.jvbabi.overmail.server.http

import es.jvbabi.overmail.server.http.avatar.item.getAvatar
import es.jvbabi.overmail.server.http.email.item.body.getEmailBody
import es.jvbabi.overmail.server.http.email.item.classify.classifyEmailRequest
import es.jvbabi.overmail.server.http.email.search.emailSearch
import es.jvbabi.overmail.server.http.labels.search.labelSearch
import es.jvbabi.overmail.server.http.stack.stackSocket
import es.jvbabi.overmail.server.http.webapp.ai.currentAiConfig
import io.ktor.http.ContentType
import io.ktor.openapi.OpenApiInfo
import io.ktor.server.application.Application
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.OpenApiDocSource
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

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

            route("/avatars") {
                route("/{avatarId}") {
                    getAvatar()
                }
            }

            route("/stack") {
                stackSocket()
            }

            route("/emails") {
                route("/search") {
                    emailSearch()
                }

                route("/{emailId}") {
                    route("/body") {
                        getEmailBody()
                    }

                    route("/classify") {
                        classifyEmailRequest()
                    }
                }
            }

            route("/labels") {
                route("/search") {
                    labelSearch()
                }
            }

            route("/webapp") {
                route("/ai") {
                    route("/current-config") {
                        currentAiConfig()
                    }
                }
            }
        }
    }
}
