package es.jvbabi.overmail.server.http

import es.jvbabi.overmail.server.database.models.EmailArchiveAction
import es.jvbabi.overmail.server.http.auth.redeem.redeemAuthCode
import es.jvbabi.overmail.server.http.avatar.item.getAvatar
import es.jvbabi.overmail.server.http.email.emailsByIds
import es.jvbabi.overmail.server.http.email.item.archive.setEmailArchiveState
import es.jvbabi.overmail.server.http.email.item.body.getEmailBody
import es.jvbabi.overmail.server.http.email.item.attachments.downloadAttachment
import es.jvbabi.overmail.server.http.email.item.download.downloadEmail
import es.jvbabi.overmail.server.http.email.item.classify.classifyEmailRequest
import es.jvbabi.overmail.server.http.email.item.labels.attachEmailLabel
import es.jvbabi.overmail.server.http.email.item.labels.detachEmailLabel
import es.jvbabi.overmail.server.http.email.item.read.setEmailRead
import es.jvbabi.overmail.server.http.email.item.shares.getShares
import es.jvbabi.overmail.server.http.email.item.shares.item.deleteShare
import es.jvbabi.overmail.server.http.email.item.shares.item.updateShare
import es.jvbabi.overmail.server.http.email.item.shares.newShare
import es.jvbabi.overmail.server.http.email.bulk.setEmailsArchiveState
import es.jvbabi.overmail.server.http.email.bulk.setEmailsRead
import es.jvbabi.overmail.server.http.email.changes.emailChanges
import es.jvbabi.overmail.server.http.email.list.emailList
import es.jvbabi.overmail.server.http.email.meta.emailsMeta
import es.jvbabi.overmail.server.http.email.list.emailListGroups
import es.jvbabi.overmail.server.http.email.list.emailListIds
import es.jvbabi.overmail.server.http.email.list.emailListIdsStream
import es.jvbabi.overmail.server.http.email.search.emailSearch
import es.jvbabi.overmail.server.http.labels.createLabel
import es.jvbabi.overmail.server.http.labels.labelsByIds
import es.jvbabi.overmail.server.http.labels.map.mapLabels
import es.jvbabi.overmail.server.http.labels.search.labelSearch
import es.jvbabi.overmail.server.http.senders.search.senderSearch
import es.jvbabi.overmail.server.http.share.downloadSharedAttachment
import es.jvbabi.overmail.server.http.share.getShare
import es.jvbabi.overmail.server.http.share.openShare
import es.jvbabi.overmail.server.http.senders.sendersByIds
import es.jvbabi.overmail.server.http.stack.stackSocket
import es.jvbabi.overmail.server.http.users.me.getCurrentUser
import es.jvbabi.overmail.server.http.users.me.inboxes.getInboxes
import es.jvbabi.overmail.server.http.users.me.inboxes.item.deleteInbox
import es.jvbabi.overmail.server.http.users.me.inboxes.item.getInbox
import es.jvbabi.overmail.server.http.users.me.inboxes.item.streamInboxFoldersForInbox
import es.jvbabi.overmail.server.http.users.me.inboxes.item.testInboxLogin
import es.jvbabi.overmail.server.http.users.me.inboxes.item.updateInbox
import es.jvbabi.overmail.server.http.users.me.inboxes.item.setInboxPaused
import es.jvbabi.overmail.server.http.users.me.inboxes.create.folders.streamInboxFolders
import es.jvbabi.overmail.server.http.users.me.inboxes.create.submit.inboxSubmitRoute
import es.jvbabi.overmail.server.http.users.me.inboxes.create.test.testImapHost
import es.jvbabi.overmail.server.http.users.me.inboxes.create.test.testImapLogin
import es.jvbabi.overmail.server.http.users.me.knowledge.createKnowledgeEntry
import es.jvbabi.overmail.server.http.users.me.knowledge.getKnowledgeEntries
import es.jvbabi.overmail.server.http.users.me.knowledge.item.deleteKnowledgeEntry
import es.jvbabi.overmail.server.http.users.me.knowledge.item.updateKnowledgeEntry
import es.jvbabi.overmail.server.http.users.me.sessions.getSessions
import es.jvbabi.overmail.server.http.users.me.sessions.item.revokeSession
import es.jvbabi.overmail.server.http.users.me.views.createView
import es.jvbabi.overmail.server.http.users.me.views.item.deleteView
import es.jvbabi.overmail.server.http.users.me.views.item.updateView
import es.jvbabi.overmail.server.http.webapp.ai.aiSocket
import es.jvbabi.overmail.server.http.webapp.ai.chat.chatHistory
import es.jvbabi.overmail.server.http.webapp.ai.chat.chatMessageStream
import es.jvbabi.overmail.server.http.webapp.ai.chat.message
import es.jvbabi.overmail.server.http.webapp.ai.chat.retryMessage
import es.jvbabi.overmail.server.http.webapp.ai.currentAiConfig
import es.jvbabi.overmail.server.http.webapp.content.contentSocket
import es.jvbabi.overmail.server.http.webapp.devices.createAuthCode
import es.jvbabi.overmail.server.http.webapp.home.homeSocket
import es.jvbabi.overmail.server.http.webapp.listing.listingSocket
import es.jvbabi.overmail.server.http.webapp.views.viewsSocket
import io.ktor.http.ContentType
import io.ktor.openapi.OpenApiInfo
import io.ktor.server.application.Application
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.OpenApiDocSource
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/** What the spec says about the api as a whole, above the list of operations. */
private const val API_DESCRIPTION = """
Everything under `/api` except the sign-in flow's own screens.

**Authentication.** Operations with a lock need a signed-in user: the session JWT from the
`overmail_session` cookie, or the same token as `Authorization: Bearer`. Without one they answer
401.

**Errors.** Every failing request answers with an `ApiErrorBody`. `error.code` is what a client
branches on (`unauthenticated`, `forbidden`, `not_found`, `invalid_request`, `conflict`, `gone`,
`internal`), `error.details` names what it was about.

**Timestamps.** The mail routes send whole seconds since the epoch; knowledge entries and sessions
send ISO-8601 strings. Each field says which.

**Streams.** What a screen keeps current travels over WebSockets, which this document cannot
describe: `/api/stack`, `/api/webapp/content/socket`, `/api/webapp/home/socket`,
`/api/webapp/listing/socket`, `/api/webapp/views/socket` and `/api/webapp/ai/socket`. The same goes
for the server-sent events of an answer of the assistant,
`/api/webapp/ai/chat/{chatId}/message/{messageId}/stream`.
"""

/**
 * Every route this server owns. Caddy forwards /api* unchanged and sends everything else to
 * SvelteKit, so all of them live under /api.
 */
internal fun Application.configureRouting() {
    routing {
        route("/api") {
            // Reads the live routing tree, so every route below shows up without a checked-in spec.
            // Every handler documents itself in the KDoc above it; the comments on the routes here
            // only carry what a whole subtree shares -- its path parameters and its errors. Prose
            // becomes the summary of everything below, so there is none, except where one handler
            // serves several routes and each of them names itself here.
            swaggerUI("/swagger") {
                info = OpenApiInfo(title = "Overmail", version = "1.0", description = API_DESCRIPTION.trim())
                source = OpenApiDocSource.Routing(ContentType.Application.Json)
                // Default is documentation.yaml, but the source above emits JSON.
                remotePath = "documentation.json"

                // In the order Swagger UI lists them. A handler names its tag itself -- one word, the
                // plugin cuts a tag at its first space. One that is not declared here still shows
                // up, just without a description and at the end.
                tag("System", "Whether the server is up.")
                tag("Authentication", "Signing in: the web sign-in flow, and pairing the app through a device code.")
                tag("Account", "Who is signed in, and the addresses they receive mail under.")
                tag("Sessions", "The devices the current user is signed in on.")
                tag("Emails", "One mail or a selection of them: lookup, body, source, attachments, state and labels.")
                tag("Listing", "What a listing holds -- its groups, a page of a group, a whole group -- and the quick search.")
                tag("Shares", "Links that hand one mail out to somebody without an account, as the owner manages them.")
                tag("Public", "What a share link opens. No session: holding the link is the whole authorization.")
                tag("Labels", "The labels of the current user.")
                tag("Senders", "The address book: correspondents by id and by search.")
                tag("Avatars", "Pictures of correspondents.")
                tag("Views", "Saved ways of looking at the mailbox, as the sidebar lists them.")
                tag("Inboxes", "Connected IMAP mailboxes: reading, editing, pausing and disconnecting them.")
                tag("Setup", "The \"new inbox\" dialog: probing a server and a login, scanning folders, creating the inbox.")
                tag("Knowledge", "What the assistant knows about the current user.")
                tag("Assistant", "Chatting with the assistant: asking, following an answer, reading a chat back.")
            }

            /**
             * Report whether the server is up.
             *
             * Tag: System
             *
             * Responses:
             *   - 200 text/plain [String] Always `ok`
             */
            get("/health") {
                call.respondText("ok")
            }

            route("/auth") {
                route("redeem") {
                    redeemAuthCode()
                }
            }

            /**
             * Responses:
             *   - 401 [es.jvbabi.overmail.server.http.api.ApiErrorBody] Not signed in
             */
            route("/avatars") {
                /**
                 * Path: avatarId [kotlin.uuid.Uuid] The picture, as `avatar_url` names it
                 */
                route("/{avatarId}") {
                    getAvatar()
                }
            }

            route("/stack") {
                stackSocket()
            }

            /**
             * Responses:
             *   - 401 [es.jvbabi.overmail.server.http.api.ApiErrorBody] Not signed in
             */
            route("/emails") {
                // GET /emails?ids=a,b,c -- what a client-side cache asks for the ids it lacks.
                emailsByIds()

                // QUERY /emails/meta -- the whole metadata of a stretch, for a client database.
                route("/meta") {
                    emailsMeta()
                }

                // GET /emails/changes -- what a client database applies on top of that.
                route("/changes") {
                    emailChanges()
                }

                // What the routes under /{emailId} do to one mail, for a whole selection: the ids
                // come in the body, because a picked stretch of the mailbox does not fit in a url.
                route("/bulk") {
                    /** Mark a selection of mails as read. */
                    route("/read") { setEmailsRead(isRead = true) }

                    /** Mark a selection of mails as unread. */
                    route("/unread") { setEmailsRead(isRead = false) }

                    /** Archive a selection of mails. */
                    route("/archive") { setEmailsArchiveState(EmailArchiveAction.Archive) }

                    /** Move a selection of mails back into the mailbox. */
                    route("/unarchive") { setEmailsArchiveState(EmailArchiveAction.Unarchive) }
                }

                route("/list") {
                    emailList()

                    route("/groups") {
                        emailListGroups()
                    }

                    // GET /emails/list/ids?by=&group= -- a whole group at once, which is what
                    // picking one in the table needs.
                    route("/ids") {
                        emailListIds()

                        // GET /emails/list/ids/stream?by= -- the same ids per group, kept current.
                        route("/stream") {
                            emailListIdsStream()
                        }
                    }
                }

                route("/search") {
                    emailSearch()
                }

                /**
                 * Path: emailId [kotlin.uuid.Uuid] The mail
                 *
                 * Responses:
                 *   - 403 [es.jvbabi.overmail.server.http.api.ApiErrorBody] The mail belongs to somebody else
                 *   - 404 [es.jvbabi.overmail.server.http.api.ApiErrorBody] No such mail
                 */
                route("/{emailId}") {
                    route("/body") {
                        getEmailBody()
                    }

                    // The source itself, as a file; `/body` is the parsed halves of it.
                    route("/download") {
                        downloadEmail()
                    }

                    /**
                     * Path: attachmentId [kotlin.uuid.Uuid] The attachment
                     */
                    route("/attachments/{attachmentId}") {
                        downloadAttachment()
                    }

                    route("/classify") {
                        classifyEmailRequest()
                    }

                    route("/shares") {
                        getShares()
                        newShare()

                        /**
                         * Path: shareId [kotlin.uuid.Uuid] The share
                         */
                        route("/{shareId}") {
                            updateShare()
                            deleteShare()
                        }
                    }

                    // One route per state rather than a body that names it: they are separate
                    // actions to a reader, and this keeps them separate in the api too.
                    /** Mark a mail as read. */
                    route("/read") {
                        setEmailRead(isRead = true)
                    }

                    /** Mark a mail as unread. */
                    route("/unread") {
                        setEmailRead(isRead = false)
                    }

                    /** Archive a mail. */
                    route("/archive") {
                        setEmailArchiveState(EmailArchiveAction.Archive)
                    }

                    /** Move a mail back into the mailbox. */
                    route("/unarchive") {
                        setEmailArchiveState(EmailArchiveAction.Unarchive)
                    }

                    /** File a mail as spam. */
                    route("/spam") {
                        setEmailArchiveState(EmailArchiveAction.Spam)
                    }

                    // The pair addresses the assignment; there is no id for it, see
                    // `EmailLabels`.
                    /**
                     * Path: labelId [kotlin.uuid.Uuid] The label
                     */
                    route("/labels/{labelId}") {
                        attachEmailLabel()
                        detachEmailLabel()
                    }
                }
            }

            // What a share link resolves to. No session anywhere below here: holding the link
            // is the whole authorization, see `getShare`.
            /**
             * Path: shareId [String] The share, as a uuid with or without its hyphens
             *
             * Responses:
             *   - 404 [es.jvbabi.overmail.server.http.api.ApiErrorBody] No such share
             *   - 410 [es.jvbabi.overmail.server.http.api.ApiErrorBody] The share has run out
             */
            route("/shares/{shareId}") {
                getShare()

                route("/open") {
                    openShare()
                }

                /**
                 * Path: attachmentId [kotlin.uuid.Uuid] The attachment
                 */
                route("/attachments/{attachmentId}") {
                    downloadSharedAttachment()
                }
            }

            /**
             * Responses:
             *   - 401 [es.jvbabi.overmail.server.http.api.ApiErrorBody] Not signed in
             */
            route("/labels") {
                labelsByIds()
                createLabel()

                route("/map") {
                    mapLabels()
                }

                route("/search") {
                    labelSearch()
                }
            }

            /**
             * Responses:
             *   - 401 [es.jvbabi.overmail.server.http.api.ApiErrorBody] Not signed in
             */
            route("/users") {
                route("/me") {
                    getCurrentUser()

                    // What the assistant learned about this user; the agent reaches the same
                    // rows through `KnowledgeStore`.
                    route("/knowledge") {
                        getKnowledgeEntries()
                        createKnowledgeEntry()

                        /**
                         * Path: knowledgeId [kotlin.uuid.Uuid] The entry
                         *
                         * Responses:
                         *   - 404 [es.jvbabi.overmail.server.http.api.ApiErrorBody] No such entry, or not one of the current user's
                         */
                        route("/{knowledgeId}") {
                            updateKnowledgeEntry()
                            deleteKnowledgeEntry()
                        }
                    }

                    // Where this user is signed in, and signing a device out again.
                    route("/sessions") {
                        getSessions()

                        /**
                         * Path: sessionId [kotlin.uuid.Uuid] The session
                         *
                         * Responses:
                         *   - 403 [es.jvbabi.overmail.server.http.api.ApiErrorBody] The session belongs to somebody else
                         *   - 404 [es.jvbabi.overmail.server.http.api.ApiErrorBody] No such session
                         */
                        route("/{sessionId}") {
                            revokeSession()
                        }
                    }

                    route("/views") {
                        route("/new") {
                            createView()
                        }

                        /**
                         * Path: viewId [kotlin.uuid.Uuid] The view
                         *
                         * Responses:
                         *   - 404 [es.jvbabi.overmail.server.http.api.ApiErrorBody] No such view, or not one of the current user's
                         */
                        route("/{viewId}") {
                            updateView()
                            deleteView()
                        }
                    }

                    route("/inboxes") {
                        getInboxes()

                        // What the edit screen opens on, saves through, and checks against.
                        /**
                         * Path: inboxId [kotlin.uuid.Uuid] The inbox
                         *
                         * Responses:
                         *   - 404 [es.jvbabi.overmail.server.http.api.ApiErrorBody] No such inbox, or not one of the current user's
                         */
                        route("/{inboxId}") {
                            getInbox()
                            updateInbox()
                            deleteInbox()

                            route("/test") {
                                route("/imap-login") {
                                    testInboxLogin()
                                }
                            }

                            route("/folders") {
                                route("/stream") {
                                    streamInboxFoldersForInbox()
                                }
                            }

                            // One route per state, like the read and archive routes above.
                            /** Pause the importer of an inbox. */
                            route("/pause") {
                                setInboxPaused(paused = true)
                            }

                            /** Resume the importer of an inbox. */
                            route("/resume") {
                                setInboxPaused(paused = false)
                            }
                        }

                        route("/create") {
                            route("/folders") {
                                route("/stream") {
                                    streamInboxFolders()
                                }
                            }

                            // The only step of the dialog that writes anything.
                            route("/submit") {
                                inboxSubmitRoute()
                            }

                            // What the "new inbox" dialog checks a half-filled form against, before
                            // there is an inbox to create.
                            route("/test") {
                                route("/imap-host") {
                                    testImapHost()
                                }

                                route("/imap-login") {
                                    testImapLogin()
                                }
                            }
                        }
                    }
                }
            }

            /**
             * Responses:
             *   - 401 [es.jvbabi.overmail.server.http.api.ApiErrorBody] Not signed in
             */
            route("/senders") {
                sendersByIds()

                route("/search") {
                    senderSearch()
                }
            }

            /**
             * Responses:
             *   - 401 [es.jvbabi.overmail.server.http.api.ApiErrorBody] Not signed in
             */
            route("/webapp") {
                route("/content") {
                    route("/socket") {
                        contentSocket()
                    }
                }

                route("/devices") {
                    route("/auth") {
                        route("/generate-auth-code") {
                            createAuthCode()
                        }
                    }
                }

                route("/home") {
                    route("/socket") {
                        homeSocket()
                    }
                }

                route("/listing") {
                    route("/socket") {
                        listingSocket()
                    }
                }

                route("/views") {
                    route("/socket") {
                        viewsSocket()
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

                        /**
                         * Path: chatId [kotlin.uuid.Uuid] The chat
                         *
                         * Responses:
                         *   - 403 [es.jvbabi.overmail.server.http.api.ApiErrorBody] The chat belongs to somebody else
                         *   - 404 [es.jvbabi.overmail.server.http.api.ApiErrorBody] No such chat
                         */
                        route("/{chatId}") {
                            route("/history") {
                                chatHistory()
                            }

                            /**
                             * Path: messageId [kotlin.uuid.Uuid] A message of that chat
                             */
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
