package es.jvbabi.overmail.server.http.users.me.inboxes.item

import es.jvbabi.overmail.server.database.models.ImapAccount
import es.jvbabi.overmail.server.database.models.ImapAccountFolderSync
import es.jvbabi.overmail.server.database.models.ImapAccountFolderSyncs
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.http.api.ApiErrorCode
import es.jvbabi.overmail.server.http.api.ApiException
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.invalidRequest
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import es.jvbabi.overmail.server.http.users.me.inboxes.create.submit.lookUpNthNewestDates
import es.jvbabi.overmail.server.jobs.importer.ImporterManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.application
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.put
import kotlin.time.Instant
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Saves the edit screen: `PUT /api/users/me/inboxes/{inboxId}`.
 *
 * Everything the screen can change goes in one request -- the connection, the login, and which
 * folders are synced with what. The folder rows are replaced wholesale rather than diffed: the
 * screen sends the complete set it is showing, so anything missing from it is a folder the user
 * took out, and a diff would only be a slower way of arriving at the same table.
 *
 * A blank password means the stored one stays; see [resolveInboxCredentials] for why an edit
 * screen cannot do anything else.
 *
 * The importer is restarted afterwards, off the response, for the same reason the submit route
 * does it: stopping waits for the mail in flight and starting opens connections.
 */
fun Route.updateInbox() {
    authenticate {
        put {
            val userId = call.requireAuthenticatedUserId()
            val database = call.database()
            val importerManager = call.application.dependencies.resolve<ImporterManager>()

            val inboxId = inboxIdFromPath(call.parameters["inboxId"])
            val request = call.receive<UpdateInboxRequest>()

            if (request.folderSettings.isEmpty()) invalidRequest("folder_settings", "an inbox needs a folder")
            val duplicate = request.folderSettings
                .groupingBy { it.folderName }
                .eachCount()
                .entries
                .firstOrNull { it.value > 1 }
            if (duplicate != null) invalidRequest("folder_settings", "is listed twice", duplicate.key)

            // Resolves the password and refuses a mailbox that is not this user's, in one step.
            val credentials = resolveInboxCredentials(
                database = database,
                userId = userId,
                inboxId = inboxId,
                host = request.imap.host,
                port = request.imap.port,
                username = request.imap.username,
                password = request.imap.password,
            )

            // Moving an account onto a host/login somebody already has would leave two importers
            // on one mailbox, writing the same mail twice.
            val clashes = database.query {
                ImapAccounts
                    .select(ImapAccounts.id)
                    .where {
                        (ImapAccounts.user eq userId) and
                            (ImapAccounts.id neq inboxId) and
                            (ImapAccounts.host eq credentials.host) and
                            (ImapAccounts.port eq credentials.port) and
                            (ImapAccounts.username eq credentials.username)
                    }
                    .count() > 0L
            }
            if (clashes) {
                throw ApiException(
                    status = HttpStatusCode.Conflict,
                    code = ApiErrorCode.CONFLICT,
                    message = "Another IMAP account with the same host, port and username already exists for this user.",
                )
            }

            // Over imap, so before the transaction: see the submit route.
            val nthNewestDates = lookUpNthNewestDates(
                host = credentials.host,
                port = credentials.port,
                username = credentials.username,
                password = credentials.password,
                folders = request.folderSettings
                    .mapNotNull { settings ->
                        val count = settings.aiImport.count
                        if (settings.aiImport.type == "newest_messages" && count != null) {
                            settings.folderName to count
                        } else {
                            null
                        }
                    }
                    .toMap(),
            )

            database.query {
                ImapAccounts.update({ ImapAccounts.id eq inboxId }) {
                    it[host] = credentials.host
                    it[port] = credentials.port
                    it[username] = credentials.username
                    it[password] = credentials.password
                }

                ImapAccountFolderSyncs.deleteWhere { ImapAccountFolderSyncs.imapAccount eq inboxId }

                val account = ImapAccount.findById(inboxId) ?: return@query
                request.folderSettings.forEach { settings ->
                    ImapAccountFolderSync.new {
                        this.imapAccount = account
                        this.folder = settings.folderName
                        this.imapPush = settings.imapPush
                        this.aiImport = settings.aiImport.stored(nthNewestDates[settings.folderName])
                    }
                }
            }

            call.respond(HttpStatusCode.OK, UpdateInboxResponse(id = inboxId))

            call.application.launch {
                importerManager.reboot(inboxId)
            }
        }
    }
}

/**
 * The scope as it is stored. `newest_messages` is resolved to a date first; a folder holding fewer
 * mails than were asked for has its whole history inside the choice, which is `all_messages`.
 */
private fun UpdateInboxRequest.FolderSettings.WireAiScope.stored(
    resolvedDate: Instant?,
): ImapAccountFolderSync.AiImportSettings = when (type) {
    "all_messages" -> ImapAccountFolderSync.AiImportSettings.AllMessages

    "after_date" -> ImapAccountFolderSync.AiImportSettings.AfterDate(
        Instant.fromEpochSeconds(timestamp ?: 0L),
    )

    "newest_messages" ->
        if (resolvedDate == null) ImapAccountFolderSync.AiImportSettings.AllMessages
        else ImapAccountFolderSync.AiImportSettings.AfterDate(resolvedDate)

    else -> ImapAccountFolderSync.AiImportSettings.OnlyNewMessages
}

@Serializable
internal data class UpdateInboxRequest(
    @SerialName("imap") val imap: Imap,
    @SerialName("folder_settings") val folderSettings: List<FolderSettings>,
) {
    @Serializable
    data class Imap(
        @SerialName("host") val host: String,
        @SerialName("port") val port: Int,
        @SerialName("username") val username: String,
        /** Empty keeps the stored one; the screen never receives it to send back. */
        @SerialName("password") val password: String = "",
    )

    @Serializable
    data class FolderSettings(
        @SerialName("folder_name") val folderName: String,
        @SerialName("imap_push") val imapPush: Boolean,
        @SerialName("ai_import") val aiImport: WireAiScope,
    ) {
        /**
         * Flat rather than a sealed class, unlike the submit route.
         *
         * The edit screen reads these back out of `GET /inboxes/{id}` and sends them again
         * unchanged, so one shape has to survive the round trip in both directions.
         */
        @Serializable
        data class WireAiScope(
            @SerialName("type") val type: String,
            @SerialName("timestamp") val timestamp: Long? = null,
            @SerialName("count") val count: Int? = null,
        )
    }
}

@Serializable
private data class UpdateInboxResponse(
    @SerialName("id") val id: kotlin.uuid.Uuid,
)
