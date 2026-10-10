package es.jvbabi.overmail.server.http.users.me.inboxes.create.submit

import es.jvbabi.overmail.kamel.ImapClient
import es.jvbabi.overmail.server.database.models.ImapAccount
import es.jvbabi.overmail.server.database.models.ImapAccountFolderSync
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.database.models.OAuthGrants
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.http.api.ApiErrorCode
import es.jvbabi.overmail.server.http.api.ApiException
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.dependency
import es.jvbabi.overmail.server.http.api.notFound
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import es.jvbabi.overmail.server.http.api.requireOwnedOAuthOnboardingFromUrl
import es.jvbabi.overmail.server.http.api.invalidRequest
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUser
import es.jvbabi.overmail.server.http.api.requireThat
import es.jvbabi.overmail.server.jobs.importer.legacy.LegacyImporterManager
import es.jvbabi.overmail.server.oauth.OAuthTokens
import io.ktor.server.application.ApplicationCall
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.JsonSchema
import io.ktor.server.application.application
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.koin.ktor.ext.get

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
        /**
         * Create an inbox.
         *
         * Description: The last step of the setup dialog. `newest_messages` is resolved to a date before it is stored; the importer starts afterwards.
         *
         * Tag: Setup
         *
         * Body: [SubmitInboxRequest] The inbox
         *
         * Responses:
         *   - 201 [SubmitInboxResponse] The new inbox
         *   - 400 [es.jvbabi.overmail.server.http.api.ApiErrorBody] A blank host, username or password, an invalid port, or no folders or one listed twice
         *   - 409 [es.jvbabi.overmail.server.http.api.ApiErrorBody] The user has an inbox with the same host, port and username
         */
        post {
            val user = call.requireAuthenticatedUser()
            val request = call.receive<SubmitInboxRequest>()

            val host = request.imap.host.trim()
            if (host.isEmpty()) invalidRequest("host", "an imap server needs a host")
            if (request.imap.port !in 1..65535) invalidRequest("port", "is not a port", request.imap.port.toString())
            if (request.imap.username.isEmpty()) invalidRequest("username", "a login needs a username")
            // An empty one is what an inbox signed in to at a provider stores, and it logs in with
            // its grant instead -- one created here would log in with nothing.
            if (request.imap.password.isEmpty()) invalidRequest("password", "a login needs a password")

            call.createInbox(
                userId = user.id.value,
                host = host,
                port = request.imap.port,
                username = request.imap.username,
                password = request.imap.password,
                auth = ImapClient.Auth.BasicAuth(request.imap.username, request.imap.password),
                oauthGrantId = null,
                folderSettings = request.folderSettings,
            )
        }
    }
}

/**
 * The same last step for a mailbox signed in to at a provider:
 * `POST /api/users/me/inboxes/create/oauth/onboardings/{onboardingId}/submit`.
 *
 * Only the folders are sent. Host, address and login all come with the sign-in, whose grant the
 * new inbox then logs in with -- it stops being an onboarding here.
 */
fun Route.oauthInboxSubmitRoute() {
    authenticate {
        /**
         * Create an inbox signed in to at a provider.
         *
         * Description: The last step of the setup dialog after a sign-in at a provider. Like `POST /api/users/me/inboxes/create/submit`, with the connection taken from the sign-in.
         *
         * Tag: Setup
         *
         * Body: [SubmitOAuthInboxRequest] The folders
         *
         * Responses:
         *   - 201 [SubmitInboxResponse] The new inbox
         *   - 400 [es.jvbabi.overmail.server.http.api.ApiErrorBody] No folders, or one listed twice
         *   - 409 [es.jvbabi.overmail.server.http.api.ApiErrorBody] The user already has an inbox for this mailbox
         */
        post {
            val onboarding = call.requireOwnedOAuthOnboardingFromUrl()
            // It re-authenticated an inbox that exists; there is nothing to create.
            if (onboarding.reauthenticatedInboxId != null) notFound("oauth_onboarding")
            val request = call.receive<SubmitOAuthInboxRequest>()
            val bearer = call.dependency<OAuthTokens>().accessToken(onboarding.grantId) ?: notFound("oauth_onboarding")

            call.createInbox(
                userId = call.requireAuthenticatedUserId(),
                host = onboarding.provider.imapHost,
                port = onboarding.provider.imapPort,
                username = onboarding.address,
                // Never asked for: the importer logs in with the grant, see ImapAccounts.password.
                password = "",
                auth = ImapClient.Auth.BearerAuth(onboarding.address, bearer),
                oauthGrantId = onboarding.grantId,
                folderSettings = request.folderSettings,
            )
        }
    }
}

/**
 * Creates the inbox, answers with it and starts its importer -- what both submits share once they
 * know what to connect to. [auth] is what the "newest n" lookup logs in with; [oauthGrantId] the
 * grant the inbox logs in with from here on, if it was signed in to at a provider.
 */
private suspend fun ApplicationCall.createInbox(
    userId: Uuid,
    host: String,
    port: Int,
    username: String,
    password: String,
    auth: ImapClient.Auth,
    oauthGrantId: Uuid?,
    folderSettings: List<SubmitInboxRequest.FolderSettings>,
) {
    val database = database()
    val importerManager = application.get<LegacyImporterManager>()

    if (folderSettings.isEmpty()) invalidRequest("folder_settings", "an inbox needs a folder")

    val duplicateFolder = folderSettings
        .groupingBy { it.folderName }
        .eachCount()
        .entries
        .firstOrNull { it.value > 1 }
    if (duplicateFolder != null) invalidRequest("folder_settings", "is listed twice", duplicateFolder.key)

    requireThat(database.query {
        ImapAccounts
            .select(ImapAccounts.id)
            .where { ImapAccounts.user eq userId }
            .andWhere { ImapAccounts.host eq host }
            .andWhere { ImapAccounts.port eq port }
            .andWhere { ImapAccounts.username eq username }
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
        port = port,
        auth = auth,
        folders = folderSettings
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
            this.port = port
            this.username = username
            this.password = password
            this.user = User[userId]
        }

        if (oauthGrantId != null) {
            // Only while it is still an onboarding: two submits of one sign-in get one inbox, and
            // the second rolls back with a 404.
            val claimed = OAuthGrants.update({ (OAuthGrants.id eq oauthGrantId) and OAuthGrants.imapAccount.isNull() }) {
                it[OAuthGrants.imapAccount] = imapAccount.id
                it[OAuthGrants.onboardingId] = null
            }
            if (claimed == 0) notFound("oauth_onboarding")
        }

        folderSettings.forEach { settings ->
            ImapAccountFolderSync.new {
                this.imapAccount = imapAccount
                this.folder = settings.folderName
                this.imapPush = settings.imapPush
                this.aiImport = settings.aiImport.stored(nthNewestDates[settings.folderName])
            }
        }

        imapAccount.id.value
    }

    respond(HttpStatusCode.Created, SubmitInboxResponse(id = accountId))

    // After the answer, and on the application's scope rather than the call's: the call
    // scope ends with the response, and this outlives it by design.
    application.launch {
        importerManager.reboot(accountId)
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
    @JsonSchema.Description("Every folder to sync")
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
        @JsonSchema.Description("Whether the folder is watched over an open connection rather than only polled")
        @SerialName("imap_push") val imapPush: Boolean,
        @JsonSchema.Description("Which of its mails the assistant classifies")
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
            @JsonSchema.Description("In whole seconds since the epoch")
            data class AfterDate(@SerialName("timestamp") val timestamp: Long) : AiImportSettings()

            /**
             * The newest [count] mails of the folder. Turned into a date before it is stored, see
             * [lookUpNthNewestDates] -- the importer works per mail and cannot count backwards
             * from a total that keeps moving.
             */
            @Serializable
            @SerialName("newest_messages")
            @JsonSchema.Description("How many of the newest mails of the folder")
            data class NewestMessages(@SerialName("count") val count: Int) : AiImportSettings()
        }
    }
}

@Serializable
private data class SubmitOAuthInboxRequest(
    @JsonSchema.Description("Every folder to sync")
    @SerialName("folder_settings") val folderSettings: List<SubmitInboxRequest.FolderSettings>,
)

@Serializable
private data class SubmitInboxResponse(
    @JsonSchema.Description("The new inbox")
    @SerialName("id") val id: Uuid,
)
