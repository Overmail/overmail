package es.jvbabi.overmail.server.http.users.me.inboxes.create.submit

import es.jvbabi.overmail.server.database.models.ImapAccount
import es.jvbabi.overmail.server.database.models.ImapAccountFolderSync
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.http.api.ApiErrorCode
import es.jvbabi.overmail.server.http.api.ApiException
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.invalidRequest
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUser
import es.jvbabi.overmail.server.http.api.requireThat
import es.jvbabi.overmail.server.jobs.importer.ImporterManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.application
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select

/**
 * Creates the inbox the "new inbox" dialog was filling in:
 * `POST /api/users/me/inboxes/create/submit`.
 *
 * The last step of the dialog, and the first one that writes anything: the three before it only
 * asked the server questions. What arrives here is the whole form -- the connection, and one
 * setting block per folder the user kept.
 *
 * Two things happen beyond the insert. A folder whose assistant scope is "the newest n mails" is
 * resolved to a date first, see [lookUpNthNewestDates]. And the importer for the account is
 * (re)started *after* the response has gone out, because it opens connections and walks whole
 * folders -- work no caller should be made to wait for.
 */
fun Route.inboxSubmitRoute() {
    authenticate {
        post {
            val database = call.database()
            val importerManager = call.application.dependencies.resolve<ImporterManager>()

            val user = call.requireAuthenticatedUser()
            val request = call.receive<SubmitInboxRequest>()

            val host = request.imap.host.trim()
            if (host.isEmpty()) invalidRequest("host", "an imap server needs a host")
            if (request.imap.port !in 1..65535) invalidRequest("port", "is not a port", request.imap.port.toString())
            if (request.imap.username.isEmpty()) invalidRequest("username", "a login needs a username")
            if (request.folderSettings.isEmpty()) invalidRequest("folder_settings", "an inbox needs a folder")

            val duplicateFolder = request.folderSettings
                .groupingBy { it.folderName }
                .eachCount()
                .entries
                .firstOrNull { it.value > 1 }
            if (duplicateFolder != null) invalidRequest("folder_settings", "is listed twice", duplicateFolder.key)

            requireThat(database.query {
                ImapAccounts
                    .select(ImapAccounts.id)
                    .where { ImapAccounts.user eq user.id }
                    .andWhere { ImapAccounts.host eq host }
                    .andWhere { ImapAccounts.port eq request.imap.port }
                    .andWhere { ImapAccounts.username eq request.imap.username }
                    .count() == 0L
            }) {
                throw ApiException(
                    status = HttpStatusCode.Conflict,
                    code = ApiErrorCode.CONFLICT,
                    message = "An IMAP account with the same host, port and username already exists for this user."
                )
            }

            // Over imap, and therefore before the transaction: holding one open across a network
            // round trip to somebody else's server is what turns a slow mailbox into a locked
            // table. Folders that do not ask for a count cost nothing here.
            val nthNewestDates = lookUpNthNewestDates(
                host = host,
                port = request.imap.port,
                username = request.imap.username,
                password = request.imap.password,
                folders = request.folderSettings
                    .mapNotNull { settings ->
                        val scope = settings.aiImport
                        if (scope is SubmitInboxRequest.FolderSettings.AiImportSettings.NewestMessages) {
                            settings.folderName to scope.count
                        } else {
                            null
                        }
                    }
                    .toMap(),
            )

            val accountId = database.query {
                val imapAccount = ImapAccount.new {
                    this.host = host
                    this.port = request.imap.port
                    this.username = request.imap.username
                    this.password = request.imap.password
                    this.user = user
                }

                request.folderSettings.forEach { folderSettings ->
                    ImapAccountFolderSync.new {
                        this.imapAccount = imapAccount
                        this.folder = folderSettings.folderName
                        this.imapPush = folderSettings.imapPush
                        this.aiImport = folderSettings.aiImport.stored(nthNewestDates[folderSettings.folderName])
                    }
                }

                imapAccount.id.value
            }

            call.respond(HttpStatusCode.Created, SubmitInboxResponse(id = accountId))

            // After the answer, and on the application's scope rather than the call's: the call
            // scope ends with the response, and this outlives it by design.
            call.application.launch {
                importerManager.reboot(accountId)
            }
        }
    }
}

/**
 * The setting as it is stored.
 *
 * "The newest n" has no counterpart in storage: [resolvedDate] is what it was worked out to, and
 * a null one means the folder holds fewer mails than were asked for -- so the whole of it is
 * inside the choice, which is [ImapAccountFolderSync.AiImportSettings.AllMessages].
 */
private fun SubmitInboxRequest.FolderSettings.AiImportSettings.stored(
    resolvedDate: Instant?,
): ImapAccountFolderSync.AiImportSettings = when (this) {
    is SubmitInboxRequest.FolderSettings.AiImportSettings.OnlyNewMessages ->
        ImapAccountFolderSync.AiImportSettings.OnlyNewMessages

    is SubmitInboxRequest.FolderSettings.AiImportSettings.AllMessages ->
        ImapAccountFolderSync.AiImportSettings.AllMessages

    is SubmitInboxRequest.FolderSettings.AiImportSettings.AfterDate ->
        ImapAccountFolderSync.AiImportSettings.AfterDate(Instant.fromEpochSeconds(timestamp))

    is SubmitInboxRequest.FolderSettings.AiImportSettings.NewestMessages ->
        if (resolvedDate == null) ImapAccountFolderSync.AiImportSettings.AllMessages
        else ImapAccountFolderSync.AiImportSettings.AfterDate(resolvedDate)
}

@Serializable
private data class SubmitInboxRequest(
    @SerialName("imap") val imap: Imap,
    @SerialName("folder_settings") val folderSettings: List<FolderSettings>,
) {
    @Serializable
    data class Imap(
        @SerialName("host") val host: String,
        @SerialName("port") val port: Int,
        @SerialName("username") val username: String,
        @SerialName("password") val password: String,
    )

    @Serializable
    data class FolderSettings(
        @SerialName("folder_name") val folderName: String,
        /** Whether the folder is watched over an open connection rather than only polled. */
        @SerialName("imap_push") val imapPush: Boolean,
        @SerialName("ai_import") val aiImport: AiImportSettings,
    ) {
        @Serializable
        sealed class AiImportSettings {
            @Serializable
            @SerialName("only_new_messages")
            data object OnlyNewMessages : AiImportSettings()

            @Serializable
            @SerialName("all_messages")
            data object AllMessages : AiImportSettings()

            @Serializable
            @SerialName("after_date")
            data class AfterDate(@SerialName("timestamp") val timestamp: Long) : AiImportSettings()

            /**
             * The newest [count] mails of the folder. Turned into a date before it is stored, see
             * [lookUpNthNewestDates] -- the importer works per mail and cannot count backwards
             * from a total that keeps moving.
             */
            @Serializable
            @SerialName("newest_messages")
            data class NewestMessages(@SerialName("count") val count: Int) : AiImportSettings()
        }
    }
}

@Serializable
private data class SubmitInboxResponse(
    /** The account that was created; what a client would ask about it under. */
    @SerialName("id") val id: Uuid,
)
