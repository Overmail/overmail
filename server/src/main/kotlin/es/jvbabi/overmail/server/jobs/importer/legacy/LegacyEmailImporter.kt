package es.jvbabi.overmail.server.jobs.importer.legacy

import es.jvbabi.overmail.kamel.Email
import es.jvbabi.overmail.kamel.FetchRequest
import es.jvbabi.overmail.kamel.ImapClient
import es.jvbabi.overmail.kamel.ImapFolder
import es.jvbabi.overmail.server.ai.classification.EmailClassificationQueue
import es.jvbabi.overmail.server.data.notifier.MailNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.*
import es.jvbabi.overmail.server.jobs.importer.EmailInserter
import es.jvbabi.overmail.server.jobs.importer.EmailInserterImpl
import es.jvbabi.overmail.server.jobs.importer.EmailPreviewGenerator
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.*
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val POLL_INTERVAL = 5.minutes

/**
 * How long a watch holds one connection before it builds a new one.
 *
 * RFC 2177 tells clients to renew at least every 29 minutes, and middleboxes drop an idle socket
 * long before a server would -- a watch that is never renewed goes quiet without ever failing,
 * which is the one way of breaking that nothing here would notice.
 */
private val IDLE_RENEW_INTERVAL = 25.minutes

/**
 * How long one IMAP operation may take before its connection counts as gone.
 *
 * The mail library has no read timeout: a socket that stops answering without being closed -- a
 * middlebox dropping it, a server that never greets -- suspends the command forever, and with it
 * the whole importer. Generous, because fetching every envelope of a large folder is one operation.
 */
private val IMAP_OPERATION_TIMEOUT = 10.minutes

/** How long a watch waits before reconnecting. The poll keeps running meanwhile, so mail is not lost. */
private val IDLE_RETRY_INTERVAL = 30.seconds

/** What a decoder puts where a byte sequence made no sense. */
private const val REPLACEMENT_CHARACTER = '\uFFFD'

/** How many mails one fallback FETCH asks for, see `fetchMails`. */
private const val FETCH_BATCH_SIZE = 100

/** How many connections one folder may burn through in a single cycle before it is left alone. */
private const val CONNECTION_ATTEMPTS_PER_FOLDER = 3

/** How long a cycle waits before it builds the connection it needs again. */
private val RECONNECT_DELAY = 5.seconds

/**
 * Raised where a connection turned out to be gone rather than the mail being wrong.
 *
 * The distinction is what keeps a cycle from grinding through a whole folder of mails that all
 * fail for the same reason: the connection is replaced and the folder walked again.
 */
private class ConnectionLostException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * What of [ImapClient.Auth] goes into [LegacyImapConnection.signature]. Spelled out because it masks its
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
data class LegacyImapConnection(
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

    /** Changes to any of these mean the connection has to be rebuilt, see `LegacyImporterManager`. */
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

class LegacyEmailImporter(
    private val database: OvermailDatabase,
    val account: LegacyImapConnection,
    private val coroutineScope: CoroutineScope,
    private val emailInserter: EmailInserter,
    private val emailPreviewGenerator: EmailPreviewGenerator,
    private val emailClassificationQueue: EmailClassificationQueue,
    private val mailNotifier: MailNotifier,
) {

    private val logger = LoggerFactory.getLogger(LegacyEmailImporter::class.java)

    private var importerJob: Job? = null

    /**
     * Many events between two passes are one reason to look again, so the newest wins and the
     * older ones are dropped: what a watch reports is "something changed", never which mail.
     */
    private val wakeUps = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        importerJob = coroutineScope.launch {
            // Started together with the pass below, not after it: a mailbox with years of mail in
            // it takes a long time to walk, and a watch that waited for that would miss every mail
            // arriving meanwhile -- which is the mail the user is actually waiting for.
            val watches = account.folders
                .filter { it.imapPush }
                .map { folder -> launch { watch(folder) } }

            try {
                while (isActive) {
                    // One failed cycle must not end the job: nothing restarts it (LegacyImporterManager
                    // only reacts to config changes), so an uncaught error would stop the import
                    // for good. Throwable for the same reason -- the mail library answers a value
                    // it does not have with TODO(), which is an Error, not an Exception.
                    // That includes a CancellationException this job did not ask for: only
                    // stopping the importer may end the loop.
                    try {
                        importOnce()
                    } catch (e: Throwable) {
                        currentCoroutineContext().ensureActive()
                        logger.error("Import cycle failed for ${account.authentication.username}, retrying in $POLL_INTERVAL", e)
                    }
                    // Whichever comes first: the timer, or a watch saying a folder changed.
                    withTimeoutOrNull(POLL_INTERVAL) { wakeUps.receive() }
                }
            } finally {
                watches.forEach { it.cancel() }
            }
        }
    }

    /**
     * Holds an `IDLE` on [sync] and asks for a pass whenever the folder reports a change.
     *
     * A pass rather than a targeted fetch: `* n EXISTS` says how many mails the folder has now,
     * not which one is new, so there is nothing to fetch by. Looking again is cheap -- the pass
     * skips what it already has.
     *
     * Its own connection, because that is what `IDLE` is: a socket that says nothing until it has
     * something to say, and can therefore not be shared with the commands the pass runs.
     */
    private suspend fun watch(sync: LegacyImapConnection.FolderSync) {
        while (currentCoroutineContext().isActive) {
            try {
                // Renewed by building a new connection, not by re-issuing IDLE on the old one: the
                // library cannot end an IDLE that is still being read, so the next IDLE waited on
                // it forever. The timeout also covers a connect that never gets an answer.
                val folderExists = withTimeoutOrNull(IDLE_RENEW_INTERVAL) {
                    connect().use { client ->
                        val folders = client.getFolders()
                        // An account has at least an INBOX, so an empty listing is not an account
                        // without folders: it is a socket that answered nothing. Worth a retry,
                        // where a folder that is really not there is not.
                        if (folders.isEmpty()) throw ConnectionLostException("${account.authentication.username} listed no folders at all")

                        val folder = folders.firstOrNull { it.fullName == sync.folder } ?: return@use false

                        folder.getIdleFolder().use { idleFolder ->
                            idleFolder.idle {
                                onNewMessage { wakeUps.trySend(Unit) }
                                onRemovedMessage { wakeUps.trySend(Unit) }
                                onFlagChanged { _, _ -> wakeUps.trySend(Unit) }
                            }
                        }
                        // Only the timeout ends a healthy IDLE. Reconnecting right away would
                        // hammer a server that keeps dropping it, so this goes through the retry.
                        throw ConnectionLostException("the IDLE on ${sync.folder} of ${account.authentication.username} ended by itself")
                    }
                }
                if (folderExists == false) {
                    logger.warn("Cannot watch ${sync.folder} for ${account.authentication.username}: no such folder")
                    return
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                // The poll keeps running regardless, so a watch that cannot hold its connection
                // costs latency, not mail.
                logger.warn("Watch on ${sync.folder} for ${account.authentication.username} failed, retrying in $IDLE_RETRY_INTERVAL", e)
                delay(IDLE_RETRY_INTERVAL)
            }
        }
    }

    /**
     * One poll cycle over every synced folder, on a connection of its own. Not reused across
     * cycles: the pool inside [ImapClient] never evicts sockets the server has dropped in the
     * meantime and hands them out again -- a dead socket can even yield an empty mail list instead
     * of an error, which would look like an empty inbox forever.
     *
     * Every failure below is contained: a folder that fails costs that folder for this cycle, a
     * mail that fails costs that mail, and a connection that turns out to be gone is replaced and
     * the folder walked again -- what the first walk already wrote is recognised and skipped.
     */
    private suspend fun importOnce() {
        if (account.folders.isEmpty()) return

        var client = connect()
        try {
            account.folders.forEach { sync ->
                repeat(CONNECTION_ATTEMPTS_PER_FOLDER) {
                    currentCoroutineContext().ensureActive()
                    try {
                        importFolder(client, sync)
                        return@forEach
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ConnectionLostException) {
                        logger.warn("Rebuilding the connection for ${account.authentication.username}: ${e.message}", e)
                        client.closeQuietly()
                        delay(RECONNECT_DELAY)
                        client = connect()
                    } catch (e: Exception) {
                        // Whatever this folder is, the other folders of the account still import.
                        logger.error("Importing ${sync.folder} for ${account.authentication.username} failed, skipping it this cycle", e)
                        return@forEach
                    }
                }
                logger.error(
                    "Gave up on ${sync.folder} for ${account.authentication.username}: " +
                        "$CONNECTION_ATTEMPTS_PER_FOLDER connections in a row were gone. Retrying next cycle."
                )
            }
        } finally {
            client.closeQuietly()
        }
    }

    /** A client for this account. Connecting and logging in happens on the first command. */
    private suspend fun connect() = ImapClient(
        host = account.host,
        port = account.port,
        auth = account.authentication,
        debug = false,
    )

    /**
     * Selects [sync] and imports every mail in it.
     *
     * @throws ConnectionLostException if the connection is gone -- the caller replaces it and
     * calls again, rather than walking the rest of the folder over a socket that answers nothing.
     */
    private suspend fun importFolder(client: ImapClient, sync: LegacyImapConnection.FolderSync) {
        val folders = withImapTimeout("listing the folders") {
            try {
                client.getFolders()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw ConnectionLostException("listing the folders of ${account.authentication.username} failed", e)
            }
        }
        // Same as in the watch: no folder at all is a connection that said nothing, and telling
        // that apart from a renamed folder is what decides between reconnecting and skipping.
        if (folders.isEmpty()) throw ConnectionLostException("${account.authentication.username} listed no folders at all")

        val folder = folders.firstOrNull { it.fullName == sync.folder }
        if (folder == null) {
            logger.warn("No folder ${sync.folder} for ${account.authentication.username}; it may have been renamed")
            return
        }

        folder.use { selected ->
            fetchMails(selected).forEach { mail ->
                // The only place this cycle may be stopped: see the NonCancellable below.
                currentCoroutineContext().ensureActive()
                try {
                    import(mail, sync, selected)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ConnectionLostException) {
                    // Every mail after this one would fail the same way, so this one is the
                    // caller's business, not a mail to be skipped.
                    throw e
                } catch (e: Throwable) {
                    // Throwable rather than Exception: an envelope field the library has no value
                    // for answers with TODO(), and that NotImplementedError would otherwise end
                    // the import for good -- nothing restarts an importer whose job threw.
                    logger.error("Failed to import a mail for ${account.authentication.username}, skipping it", e)
                }
            }
        }
    }

    /**
     * Every mail of [folder] that can be fetched at all.
     *
     * One FETCH for the whole folder is what a healthy pass does. A single response the parser
     * cannot read fails that one FETCH though, and with it every mail in it -- so the folder is
     * retried in batches, and a batch that fails again mail by mail. One unreadable mail then
     * costs that mail and nothing around it.
     */
    private suspend fun fetchMails(folder: ImapFolder): List<Email> {
        try {
            return withImapTimeout("fetching ${folder.fullName}") { folder.getMails { importFields() } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ConnectionLostException) {
            // Timed out: the connection is stuck, and the fallback would run over the same one.
            throw e
        } catch (e: Exception) {
            logger.warn(
                "Fetching ${folder.fullName} for ${account.authentication.username} in one go failed, " +
                    "retrying in batches of $FETCH_BATCH_SIZE",
                e,
            )
        }

        val ids = withImapTimeout("listing the mails of ${folder.fullName}") { folder.getMailIds() }
        // The pass above got as far as reading a FETCH response, so this folder is not empty. An
        // empty listing now is therefore the connection answering nothing, and a fallback over it
        // would import zero mails and call that a success.
        if (ids.isEmpty()) {
            throw ConnectionLostException("${folder.fullName} of ${account.authentication.username} listed no mails right after a failed fetch")
        }

        return ids
            .chunked(FETCH_BATCH_SIZE)
            .flatMap { batch -> fetchBatch(folder, batch) }
    }

    /** [batch] in one FETCH, or every id of it on its own once that failed. */
    private suspend fun fetchBatch(folder: ImapFolder, batch: List<Int>): List<Email> {
        try {
            return withImapTimeout("fetching mails ${batch.first()}:${batch.last()} of ${folder.fullName}") {
                folder.getMails {
                    getIds(batch.map { it.toLong() })
                    importFields()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ConnectionLostException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "Fetching mails ${batch.first()}:${batch.last()} of ${folder.fullName} for " +
                    "${account.authentication.username} failed, falling back to one FETCH per mail",
                e,
            )
        }

        return batch.mapNotNull { id ->
            try {
                withImapTimeout("fetching mail $id of ${folder.fullName}") {
                    folder.getMails {
                        getId(id.toLong())
                        importFields()
                    }.firstOrNull()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ConnectionLostException) {
                throw e
            } catch (e: Exception) {
                logger.error("Mail $id of ${folder.fullName} for ${account.authentication.username} cannot be fetched, skipping it", e)
                null
            }
        }
    }

    /** What the importer reads off every mail. The body is fetched per mail, not here. */
    private fun FetchRequest.importFields() {
        envelope = true
        flags = true
        uid = true
    }

    /**
     * Fails unless [folder] still answers on its connection.
     *
     * A `SEARCH UID`, because a socket the server has dropped does not fail a fetch: the response
     * simply ends, and what comes out is an empty mail list or a body cut in half rather than an
     * error. The uid was listed by this very pass, so an answer without it is not the mail being
     * gone -- it is the connection.
     */
    private suspend fun requireLiveConnection(folder: ImapFolder, mail: Email) {
        val uid = mail.uid.await()
        val id = withImapTimeout("looking up uid $uid in ${folder.fullName}") {
            try {
                folder.getIdByUid(uid)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw ConnectionLostException("the connection of ${account.authentication.username} failed on a uid lookup", e)
            }
        }

        if (id == null) throw ConnectionLostException("${folder.fullName} of ${account.authentication.username} no longer answers for uid $uid")
    }

    /**
     * [block] under [IMAP_OPERATION_TIMEOUT].
     *
     * @throws ConnectionLostException once it ran out -- the caller replaces the connection, which
     * is also the only thing that ends the read still suspended on it.
     */
    private suspend fun <T> withImapTimeout(operation: String, block: suspend () -> T): T = try {
        withTimeout(IMAP_OPERATION_TIMEOUT) { block() }
    } catch (e: TimeoutCancellationException) {
        throw ConnectionLostException("$operation for ${account.authentication.username} got no answer within $IMAP_OPERATION_TIMEOUT", e)
    }

    /** Closing is best effort: a socket that cannot be closed must not cost the cycle. */
    private fun ImapClient.closeQuietly() {
        try {
            close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Closing the connection of ${account.authentication.username} failed", e)
        }
    }

    /**
     * Imports one mail, all of it or none of it.
     *
     * `NonCancellable`, so stopping the importer never lands between the body being downloaded and
     * the row being written -- the mail is finished, and the cycle stops at the check before the
     * next one. It is the whole reason [stop] can promise a clean end.
     *
     * The mail with its addresses, attachments and read flag is [emailInserter]'s, the preview
     * [emailPreviewGenerator]'s -- two steps, each in a transaction of its own.
     */
    private suspend fun import(
        mail: Email,
        sync: LegacyImapConnection.FolderSync,
        folder: ImapFolder,
    ) = withContext(NonCancellable) {
        val subject = mail.subject.await()
        val sentAt = mail.sentAt.await()

        // Before the body, not after: downloading it pulls the attachments too. The inserter
        // checks again, but only once it has the body.
        if (database.query { isKnown(sentAt, subject) }) return@withContext

        // The inserter refuses such a mail as well; asking the envelope saves the download.
        if (mail.from.await().isEmpty()) {
            logger.warn("Skipping mail without a From header: ${subject.forLog()}")
            return@withContext
        }

        // The body is the one expensive download of this mail and the one that pulls its
        // attachments, so the connection is made sure of first: a dropped one hands over half a
        // mail instead of failing, and half a mail would be stored as the whole of it.
        requireLiveConnection(folder, mail)

        // The download happens in the inserter, so the timeout is around all of it: it is the
        // only thing that ends a read on a socket that stopped answering.
        val email = when (val result = withImapTimeout("importing ${subject.forLog()}") { insert(mail) }) {
            EmailInserterImpl.Result.AlreadyExists -> return@withContext
            is EmailInserterImpl.Result.Imported -> result.email
        }
        email.textContent.warnIfGarbled("text", subject)
        email.htmlContent.warnIfGarbled("html", subject)
        val storedId = email.id.value

        emailPreviewGenerator.generatePreview(email)

        // Imported either way; only what the assistant reads is the user's choice per folder.
        if (sync.wantsAssistant(sentAt)) emailClassificationQueue.enqueue(storedId)
        // The mail is in the mailbox now, so anything showing or counting it is stale.
        // A mail that was not there before: every listing is one longer and one row further down.
        mailNotifier.notifyMailChanged(account.userId, storedId, movedListings = true)
    }

    /**
     * Stops the importer and waits for it to be done.
     *
     * Suspending on purpose: a mail that is halfway through being written finishes first (see
     * [import]), so nothing is left behind half-imported and the connections are closed by the
     * time this returns. The caller replacing this importer with a new one therefore never has
     * two of them on the same mailbox.
     */
    suspend fun stop() {
        importerJob?.cancelAndJoin()
        importerJob = null
    }

    /**
     * Logs a body part the mail library could not decode cleanly.
     *
     * Such a part comes back with replacement characters rather than throwing, because half a mail
     * beats no mail -- but it is worth a line in the log, or a charset the library cannot read
     * would quietly turn into a mailbox full of question marks.
     */
    private fun String?.warnIfGarbled(part: String, subject: String?) {
        if (this != null && contains(REPLACEMENT_CHARACTER)) {
            logger.warn("The $part part of ${subject.forLog()} did not decode cleanly; it is stored as it came out")
        }
    }

    /** How a subject is named in a log line, where a mail without one still has to be told apart. */
    private fun String?.forLog(): String = if (this == null) "a mail without a subject" else "\"$this\""

    /**
     * Hands [mail] to [emailInserter], which downloads its body and stores the mail, its
     * addresses, its recipients and its attachments, or reports that it is already there.
     *
     * The account is loaded for it, as this importer only holds a snapshot.
     */
    private suspend fun insert(mail: Email): EmailInserterImpl.Result {
        val imapAccount = database.query { ImapAccount[account.id] }
        return emailInserter.importEmailIntoDatabase(mail, imapAccount, mail.flags.await())
    }

    /**
     * Mails are recognised by account, send second and subject, see [Emails].
     *
     * A missing subject is compared with `IS NULL`, because NULL never equals NULL and such a mail
     * would import over and over. It also matches `""`, which is what a missing subject was stored
     * as before the column became nullable -- those mails are already here.
     */
    private fun isKnown(sent: Instant, subject: String?): Boolean =
        Emails
            .select(Emails.id)
            .where {
                (Emails.imapAccount eq account.id) and
                    (Emails.sent eq sent.truncatedToSecond()) and
                    if (subject == null) Emails.subject.isNull() or (Emails.subject eq "")
                    else Emails.subject eq subject
            }
            .empty()
            .not()


}
