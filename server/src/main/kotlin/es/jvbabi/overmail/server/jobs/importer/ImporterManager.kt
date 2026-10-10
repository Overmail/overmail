package es.jvbabi.overmail.server.jobs.importer

import es.jvbabi.overmail.core.ImapClient
import es.jvbabi.overmail.server.ai.classification.EmailClassificationQueue
import es.jvbabi.overmail.server.data.notifier.MailNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.ImapAccount
import es.jvbabi.overmail.server.database.models.OAuthGrants
import es.jvbabi.overmail.server.oauth.OAuthTokens
import kotlinx.coroutines.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** How long an added, removed or re-configured account takes to be picked up, see [start]. */
private val RELOAD_INTERVAL = 1.minutes

class ImporterManager(
    private val database: OvermailDatabase,
    private val coroutineScope: CoroutineScope,
    private val emailClassificationQueue: EmailClassificationQueue,
    private val mailNotifier: MailNotifier,
    private val oauthTokens: OAuthTokens,
) {

    private val importer = mutableMapOf<Uuid, EmailImporter>()

    /**
     * Keeps one importer per account running. Nothing pushes account changes any more, so the
     * table is re-read every [RELOAD_INTERVAL] and the difference applied.
     */
    suspend fun start() {
        while (coroutineScope.isActive) {
            reconcile(database.query {
                val grants = oauthGrantStates()
                ImapAccount.all().map { it.toConnection(grants[it.id.value]) }
            })
            delay(RELOAD_INTERVAL)
        }
    }

    /**
     * Starts the importer for [accountId] again, whatever state it was in.
     *
     * What a freshly submitted or re-configured account goes through, instead of waiting out
     * [RELOAD_INTERVAL] for the next sweep to notice it. Stopping is awaited, so the old importer
     * is finished before the new one opens a connection to the same mailbox -- two importers on
     * one account would race each other into duplicate rows.
     *
     * An account that is no longer there is stopped and not replaced.
     */
    suspend fun reboot(accountId: Uuid) {
        importer.remove(accountId)?.stop()

        val account = database.query {
            ImapAccount.findById(accountId)?.toConnection(oauthGrantStates(accountId)[accountId])
        } ?: return
        // Rebooting a paused account means leaving it stopped; the row is what decides, not the
        // caller, so a resume and a pause can go through the same door. Same for one whose sign-in
        // was refused: it is started by the new sign-in, not before.
        if (!account.canRun) return
        importer[accountId] = startImporter(account)
    }

    /**
     * Stops the importer for [accountId] and forgets it.
     *
     * What a mailbox being deleted goes through, before the row is gone: awaited (see
     * [EmailImporter.stop]), so a mail halfway through being written is finished and nothing is
     * still inserting into an account that is about to disappear.
     */
    suspend fun stop(accountId: Uuid) {
        importer.remove(accountId)?.stop()
    }

    private suspend fun reconcile(allAccounts: List<ImapConnection>) {
        // A paused account is treated as one that is not there at all: whatever importer it has is
        // stopped below and none is started for it. That also makes a pause set straight in the
        // database take hold, within RELOAD_INTERVAL -- and a grant the provider refused, which
        // would only fail every login until the user signs in again.
        val accounts = allAccounts.filter { it.canRun }
        val currentIds = accounts.map { it.id }.toSet()
        importer.keys.filterNot { it in currentIds }.toList().forEach { removedId ->
            importer.remove(removedId)?.stop()
        }

        accounts.forEach { account ->
            val existingImporter = importer[account.id]
            if (existingImporter != null) {
                if (existingImporter.account.signature == account.signature) return@forEach
                existingImporter.stop()
            }

            importer[account.id] = startImporter(account)
        }
    }

    private fun startImporter(account: ImapConnection) = EmailImporter(
        database = this.database,
        account = account,
        coroutineScope = CoroutineScope(coroutineScope.coroutineContext) + CoroutineName("EmailImporter-${account.id}"),
        emailClassificationQueue = this.emailClassificationQueue,
        mailNotifier = this.mailNotifier,
        oauthTokens = this.oauthTokens,
    ).also { it.start() }
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
