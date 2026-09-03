package es.jvbabi.overmail.server.http

import es.jvbabi.overmail.server.http.avatar.item.getAvatar
import es.jvbabi.overmail.server.http.email.item.body.getEmailBody
import es.jvbabi.overmail.server.http.email.item.classify.classifyEmailRequest
import es.jvbabi.overmail.server.http.email.emailsByIds
import es.jvbabi.overmail.server.http.email.search.emailSearch
import es.jvbabi.overmail.server.http.labels.labelsByIds
import es.jvbabi.overmail.server.http.labels.search.labelSearch
import es.jvbabi.overmail.server.http.senders.search.senderSearch
import es.jvbabi.overmail.server.http.senders.sendersByIds
import es.jvbabi.overmail.server.http.stack.stackSocket
import es.jvbabi.overmail.server.http.users.me.getCurrentUser
import es.jvbabi.overmail.server.http.webapp.ai.aiSocket
import es.jvbabi.overmail.server.http.webapp.home.homeSocket
import es.jvbabi.overmail.server.http.webapp.ai.currentAiConfig
import es.jvbabi.overmail.server.http.webapp.ai.chat.chatHistory
import es.jvbabi.overmail.server.http.webapp.ai.chat.chatMessageStream
import es.jvbabi.overmail.server.http.webapp.ai.chat.message
import es.jvbabi.overmail.server.http.webapp.ai.chat.retryMessage
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
                // GET /emails?ids=a,b,c -- what a client-side cache asks for the ids it lacks.
                emailsByIds()

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
                labelsByIds()

                route("/search") {
                    labelSearch()
                }
            }

            route("/users") {
                route("/me") {
                    getCurrentUser()
                }
            }

            route("/senders") {
                sendersByIds()

                route("/search") {
                    senderSearch()
                }
            }

            route("/webapp") {
                route("/home") {
                    route("/socket") {
                        homeSocket()
                    }
                }

                route("/ai") {
                    route("/current-config") {
                        currentAiConfig()
                    }

                    route("/socket") {
                        aiSocket()
                    }

                    route("/chat") {
                        message()

                        route("/{chatId}") {
                            route("/history") {
                                chatHistory()
                            }

                            route("/message/{messageId}") {
                                route("/stream") {
                                    chatMessageStream()
                                }

                                route("/retry") {
                                    retryMessage()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
