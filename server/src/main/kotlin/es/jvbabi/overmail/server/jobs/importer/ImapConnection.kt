package es.jvbabi.overmail.server.jobs.importer

import es.jvbabi.overmail.kamel.ImapClient
import es.jvbabi.overmail.server.database.models.ImapAccount
import es.jvbabi.overmail.server.database.models.ImapAccountFolderSync
import es.jvbabi.overmail.server.database.models.OAuthGrants
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * What of [ImapClient.Auth] goes into [ImapConnection.signature]. Spelled out because it masks its
 * secret when printed, which would make a new password look like no change. An access token is
 * left out: it changes every hour, and the job that renews it restarts the importer itself, see
 * `OAuthTokens.run`.
 */
private val ImapClient.Auth.signature: String
    get() = when (this) {
        is ImapClient.Auth.BasicAuth -> "$username:$password"
        is ImapClient.Auth.BearerAuth -> "$username:oauth"
    }

/**
 * Everything an importer needs about its account, read once while a transaction was open. The job
 * outlives that transaction by hours, which a DAO entity would not: it could no longer resolve
 * [userId] from its reference.
 */
data class ImapConnection(
    val id: Uuid,
    val userId: Uuid,
    val host: String,
    val port: Int,
    val authentication: ImapClient.Auth,
    /** The folders this account syncs, and how. Empty means nothing is imported for it. */
    val folders: List<FolderSync>,
    /** Whether the account is paused; a paused one has no importer at all. */
    val isPaused: Boolean = false,
    val requiresReauthentication: Boolean = false,
) {
    /** Whether an importer runs for the account at all. */
    val canRun: Boolean get() = !isPaused && !requiresReauthentication

    /** Changes to any of these mean the connection has to be rebuilt, see `ImporterManager`. */
    val signature: String
        get() = "$host:$port:${authentication.signature}:" +
            folders.sortedBy { it.folder }.joinToString(",") { "${it.folder}/${it.imapPush}/${it.aiImportSettings}/${it.createdAt}" }

    /** One folder's settings, as `ImapAccountFolderSyncs` holds them. */
    data class FolderSync(
        val folder: String,
        /** Whether the folder is watched over an open connection rather than only polled. */
        val imapPush: Boolean,
        val aiImportSettings: ImapAccountFolderSync.AiImportSettings,
        /** When the folder was added, which is what "only new messages" is measured against. */
        val createdAt: Instant,
    ) {
        /**
         * Whether a mail sent at [sentAt] is worth putting through the assistant.
         *
         * Every mail of a synced folder is imported either way -- this only decides what the
         * assistant is paid to read, which is what the user picked per folder.
         */
        fun wantsAssistant(sentAt: Instant): Boolean = when (val scope = aiImportSettings) {
            ImapAccountFolderSync.AiImportSettings.AllMessages -> true
            // Everything already in the folder when it was added is history; "only new" means
            // what arrives from here on.
            ImapAccountFolderSync.AiImportSettings.OnlyNewMessages -> sentAt >= createdAt
            is ImapAccountFolderSync.AiImportSettings.AfterDate -> sentAt >= scope.date
        }
    }
}

/** The grant an account logs in with, as far as the importer cares. */
internal data class OAuthGrantState(
    val id: Uuid,
    val requiresReauthentication: Boolean,
    val bearer: String,
)

/**
 * The grants of [accountId], or of every account when null, by account. One query for all of them,
 * so a sweep does not cost a query per account.
 */
internal fun oauthGrantStates(accountId: Uuid? = null): Map<Uuid, OAuthGrantState> = OAuthGrants
    .select(OAuthGrants.id, OAuthGrants.imapAccount, OAuthGrants.requiresReauthentication, OAuthGrants.accessToken)
    .where { if (accountId == null) OAuthGrants.imapAccount.isNotNull() else OAuthGrants.imapAccount eq accountId }
    .associate { row ->
        row[OAuthGrants.imapAccount]!!.value to
            OAuthGrantState(
                id = row[OAuthGrants.id].value,
                requiresReauthentication = row[OAuthGrants.requiresReauthentication],
                bearer = row[OAuthGrants.accessToken],
            )
    }

/**
 * Reads the row into the snapshot the job runs on; only valid inside the transaction. [grant] is
 * the account's, see [oauthGrantStates].
 */
internal fun ImapAccount.toConnection(grant: OAuthGrantState?) = ImapConnection(
    id = id.value,
    userId = user.id.value,
    host = host,
    port = port,
    authentication = when (grant) {
        null -> ImapClient.Auth.BasicAuth(username, password)
        else -> ImapClient.Auth.BearerAuth(username, grant.bearer)
    },
    requiresReauthentication = grant?.requiresReauthentication ?: false,
    isPaused = isPaused,
    // Read here, with the account: the importer outlives this transaction and could not follow
    // the reference afterwards.
    folders = folderSyncs.map { sync ->
        ImapConnection.FolderSync(
            folder = sync.folder,
            imapPush = sync.imapPush,
            aiImportSettings = sync.aiImport,
            createdAt = sync.createdAt,
        )
    },
)
