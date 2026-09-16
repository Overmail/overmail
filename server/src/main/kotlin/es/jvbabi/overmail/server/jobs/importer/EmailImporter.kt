package es.jvbabi.overmail.server.jobs.importer

import es.jvbabi.overmail.core.Email
import es.jvbabi.overmail.core.Email.Flag
import es.jvbabi.overmail.core.FetchRequest
import es.jvbabi.overmail.core.ImapClient
import es.jvbabi.overmail.core.ImapFolder
import es.jvbabi.overmail.server.ai.classification.EmailClassificationQueue
import es.jvbabi.overmail.server.data.notifier.MailNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.EmailRecipientType
import es.jvbabi.overmail.server.database.models.ImapAccountFolderSync
import es.jvbabi.overmail.server.database.models.EmailRecipients
import es.jvbabi.overmail.server.database.models.EmailPreviews
import es.jvbabi.overmail.server.database.models.EmailUsers
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.truncatedToSecond
import es.jvbabi.overmail.server.util.mailPreview
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.coroutineContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.insertIgnoreAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.upsert
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val POLL_INTERVAL = 5.minutes

/**
 * How often a watched folder re-issues its `IDLE`.
 *
 * RFC 2177 tells clients to renew at least every 29 minutes, and middleboxes drop an idle socket
 * long before a server would -- a watch that is never renewed goes quiet without ever failing,
 * which is the one way of breaking that nothing here would notice.
 */
private val IDLE_RENEW_INTERVAL = 25.minutes

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
 * Everything an importer needs about its account, read once while a transaction was open. The job
 * outlives that transaction by hours, which a DAO entity would not: it could no longer resolve
 * [userId] from its reference.
 */
data class ImapConnection(
    val id: Uuid,
    val userId: Uuid,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    /** The folders this account syncs, and how. Empty means nothing is imported for it. */
    val folders: List<FolderSync>,
    /** Whether the account is paused; a paused one has no importer at all. */
    val isPaused: Boolean = false,
) {
    /** Changes to any of these mean the connection has to be rebuilt, see `ImporterManager`. */
    val signature: String
        get() = "$host:$port:$username:$password:" +
            folders.sortedBy { it.folder }.joinToString(",") { "${it.folder}/${it.imapPush}/${it.aiImport}/${it.createdAt}" }

    /** One folder's settings, as `ImapAccountFolderSyncs` holds them. */
    data class FolderSync(
        val folder: String,
        /** Whether the folder is watched over an open connection rather than only polled. */
        val imapPush: Boolean,
        val aiImport: ImapAccountFolderSync.AiImportSettings,
        /** When the folder was added, which is what "only new messages" is measured against. */
        val createdAt: Instant,
    ) {
        /**
         * Whether a mail sent at [sentAt] is worth putting through the assistant.
         *
         * Every mail of a synced folder is imported either way -- this only decides what the
         * assistant is paid to read, which is what the user picked per folder.
         */
        fun wantsAssistant(sentAt: Instant): Boolean = when (val scope = aiImport) {
            ImapAccountFolderSync.AiImportSettings.AllMessages -> true
            // Everything already in the folder when it was added is history; "only new" means
            // what arrives from here on.
            ImapAccountFolderSync.AiImportSettings.OnlyNewMessages -> sentAt >= createdAt
            is ImapAccountFolderSync.AiImportSettings.AfterDate -> sentAt >= scope.date
        }
    }
}

class EmailImporter(
    private val database: OvermailDatabase,
    val account: ImapConnection,
    private val coroutineScope: CoroutineScope,
    private val emailClassificationQueue: EmailClassificationQueue,
    private val mailNotifier: MailNotifier,
) {

    private val logger = LoggerFactory.getLogger(EmailImporter::class.java)

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
                    // One failed cycle must not end the job: nothing restarts it (ImporterManager
                    // only reacts to config changes), so an uncaught error would stop the import
                    // for good. Throwable for the same reason -- the mail library answers a value
                    // it does not have with TODO(), which is an Error, not an Exception.
                    try {
                        importOnce()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        logger.error("Import cycle failed for ${account.username}, retrying in $POLL_INTERVAL", e)
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
    private suspend fun watch(sync: ImapConnection.FolderSync) {
        while (currentCoroutineContext().isActive) {
            try {
                ImapClient(
                    host = account.host,
                    port = account.port,
                    username = account.username,
                    password = account.password,
                    debug = false,
                ).use { client ->
                    val folders = client.getFolders()
                    // An account has at least an INBOX, so an empty listing is not an account
                    // without folders: it is a socket that answered nothing. Worth a retry,
                    // where a folder that is really not there is not.
                    if (folders.isEmpty()) throw ConnectionLostException("${account.username} listed no folders at all")

                    val folder = folders.firstOrNull { it.fullName == sync.folder }
                    if (folder == null) {
                        logger.warn("Cannot watch ${sync.folder} for ${account.username}: no such folder")
                        return
                    }

                    folder.getIdleFolder().use { idleFolder ->
                        while (currentCoroutineContext().isActive) {
                            // Re-issued on a timer: an IDLE nobody renews is dropped by the
                            // server or by whatever sits between, and it goes quiet rather than
                            // failing, so nothing here would ever notice.
                            withTimeoutOrNull(IDLE_RENEW_INTERVAL) {
                                idleFolder.idle {
                                    onNewMessage { wakeUps.trySend(Unit) }
                                    onRemovedMessage { wakeUps.trySend(Unit) }
                                    onFlagChanged { _, _ -> wakeUps.trySend(Unit) }
                                }
                            }
                            idleFolder.cancel()
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The poll keeps running regardless, so a watch that cannot hold its connection
                // costs latency, not mail.
                logger.warn("Watch on ${sync.folder} for ${account.username} failed, retrying in $IDLE_RETRY_INTERVAL", e)
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
                        logger.warn("Rebuilding the connection for ${account.username}: ${e.message}", e)
                        client.closeQuietly()
                        delay(RECONNECT_DELAY)
                        client = connect()
                    } catch (e: Exception) {
                        // Whatever this folder is, the other folders of the account still import.
                        logger.error("Importing ${sync.folder} for ${account.username} failed, skipping it this cycle", e)
                        return@forEach
                    }
                }
                logger.error(
                    "Gave up on ${sync.folder} for ${account.username}: " +
                        "$CONNECTION_ATTEMPTS_PER_FOLDER connections in a row were gone. Retrying next cycle."
                )
            }
        } finally {
            client.closeQuietly()
        }
    }

    /** A client for this account. Connecting and logging in happens on the first command. */
    private fun connect() = ImapClient(
        host = account.host,
        port = account.port,
        username = account.username,
        password = account.password,
        debug = false,
    )

    /**
     * Selects [sync] and imports every mail in it.
     *
     * @throws ConnectionLostException if the connection is gone -- the caller replaces it and
     * calls again, rather than walking the rest of the folder over a socket that answers nothing.
     */
    private suspend fun importFolder(client: ImapClient, sync: ImapConnection.FolderSync) {
        val folders = try {
            client.getFolders()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ConnectionLostException("listing the folders of ${account.username} failed", e)
        }
        // Same as in the watch: no folder at all is a connection that said nothing, and telling
        // that apart from a renamed folder is what decides between reconnecting and skipping.
        if (folders.isEmpty()) throw ConnectionLostException("${account.username} listed no folders at all")

        val folder = folders.firstOrNull { it.fullName == sync.folder }
        if (folder == null) {
            logger.warn("No folder ${sync.folder} for ${account.username}; it may have been renamed")
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
                    logger.error("Failed to import a mail for ${account.username}, skipping it", e)
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
            return folder.getMails { importFields() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "Fetching ${folder.fullName} for ${account.username} in one go failed, " +
                    "retrying in batches of $FETCH_BATCH_SIZE",
                e,
            )
        }

        val ids = folder.getMailIds()
        // The pass above got as far as reading a FETCH response, so this folder is not empty. An
        // empty listing now is therefore the connection answering nothing, and a fallback over it
        // would import zero mails and call that a success.
        if (ids.isEmpty()) {
            throw ConnectionLostException("${folder.fullName} of ${account.username} listed no mails right after a failed fetch")
        }

        return ids
            .chunked(FETCH_BATCH_SIZE)
            .flatMap { batch -> fetchBatch(folder, batch) }
    }

    /** [batch] in one FETCH, or every id of it on its own once that failed. */
    private suspend fun fetchBatch(folder: ImapFolder, batch: List<Int>): List<Email> {
        try {
            return folder.getMails {
                getIds(batch.map { it.toLong() })
                importFields()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "Fetching mails ${batch.first()}:${batch.last()} of ${folder.fullName} for " +
                    "${account.username} failed, falling back to one FETCH per mail",
                e,
            )
        }

        return batch.mapNotNull { id ->
            try {
                folder.getMails {
                    getId(id.toLong())
                    importFields()
                }.firstOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Mail $id of ${folder.fullName} for ${account.username} cannot be fetched, skipping it", e)
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
        val id = try {
            folder.getIdByUid(uid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ConnectionLostException("the connection of ${account.username} failed on a uid lookup", e)
        }

        if (id == null) throw ConnectionLostException("${folder.fullName} of ${account.username} no longer answers for uid $uid")
    }

    /** Closing is best effort: a socket that cannot be closed must not cost the cycle. */
    private fun ImapClient.closeQuietly() {
        try {
            close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Closing the connection of ${account.username} failed", e)
        }
    }

    /**
     * Imports one mail, all of it or none of it.
     *
     * `NonCancellable`, so stopping the importer never lands between the body being downloaded and
     * the row being written -- the mail is finished, and the cycle stops at the check before the
     * next one. It is the whole reason [stop] can promise a clean end.
     */
    private suspend fun import(
        mail: Email,
        sync: ImapConnection.FolderSync,
        folder: ImapFolder,
    ) = withContext(NonCancellable) {
        // A missing subject stores as "", never null: the dedup below compares it with
        // `=`, and NULL never equals NULL, so such mails would import over and over.
        val subject = mail.subject.await().orEmpty()
        val sentAt = mail.sentAt.await()

        // Before the body, not after: downloading it pulls the attachments too.
        if (database.query { isKnown(sentAt, subject) }) return@withContext

        val from = mail.from.await()
        val to = mail.to.await()
        val cc = mail.cc.await()
        val bcc = mail.bcc.await()

        // Only the address identifies a stored email user. The display names stay on
        // this mail: notifications@github.com carries the acting username as its name,
        // so a name learned here says nothing about the next mail from that address.
        val emailUsers = findOrCreateEmailUsers((from + to + cc + bcc).map { it.address }.distinct())

        val fromHeader = from.firstOrNull()
        if (fromHeader == null) {
            logger.warn("Skipping mail without a From header: $subject")
            return@withContext
        }

        val recipients = listOf(
            to to EmailRecipientType.RECIPIENT,
            cc to EmailRecipientType.CC,
            bcc to EmailRecipientType.BCC,
        ).flatMap { (users, type) ->
            users.map { NewRecipient(emailUsers.getValue(it.address), it.name, type) }
        }

        // The body is the one expensive download of this mail and the one that pulls its
        // attachments, so the connection is made sure of first: a dropped one hands over half a
        // mail instead of failing, and half a mail would be stored as the whole of it.
        requireLiveConnection(folder, mail)

        val content = mail.getContent()

        val storedId = insert(
            senderId = emailUsers.getValue(fromHeader.address),
            senderName = fromHeader.name,
            subject = subject,
            sent = sentAt,
            rawContent = content.raw,
            textContent = content.text.checkMailPart("text", subject),
            htmlContent = content.html.checkMailPart("html", subject),
            isRead = Flag.Seen in mail.flags.await(),
            recipients = recipients,
        )

        if (storedId != null) {
            // Imported either way; only what the assistant reads is the user's choice per folder.
            if (sync.wantsAssistant(sentAt)) emailClassificationQueue.enqueue(storedId)
            // The mail is in the mailbox now, so anything showing or counting it is stale.
            // A mail that was not there before: every listing is one longer and one row further down.
            mailNotifier.notifyMailChanged(account.userId, storedId, movedListings = true)
        }
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
     * A body part as the mail library decoded it, or null if it is blank.
     *
     * A part that does not decode cleanly comes back with replacement characters rather than
     * throwing, because half a mail beats no mail -- but it is worth a line in the log, or a
     * charset the library cannot read would quietly turn into a mailbox full of question marks.
     */
    private fun String?.checkMailPart(part: String, subject: String): String? {
        if (this == null) return null
        if (contains(REPLACEMENT_CHARACTER)) {
            logger.warn("The $part part of \"$subject\" did not decode cleanly; it is stored as it came out")
        }

        return takeIf { it.isNotBlank() }
    }

    /**
     * Resolves the header addresses to [EmailUsers] ids, inserting the ones this user has not seen
     * before. No upsert: the row holds nothing but the key, so there would be nothing to update.
     * `insertIgnore` returns null once the address is known -- including the row an importer of
     * another account of the same user just committed -- and the lookup then finds it.
     */
    private suspend fun findOrCreateEmailUsers(addresses: List<String>): Map<String, Uuid> = database.query {
        addresses.associateWith { address ->
            EmailUsers.insertIgnoreAndGetId {
                it[user] = account.userId
                it[EmailUsers.address] = address
            }?.value
                ?: EmailUsers
                    .select(EmailUsers.id)
                    .where { (EmailUsers.user eq account.userId) and (EmailUsers.address eq address) }
                    .single()[EmailUsers.id].value
        }
    }

    /**
     * Stores the mail together with its recipient links, or returns null and writes nothing if it
     * is already there. Never updates an existing mail: the local state (`is_read`) is ours, the
     * server's copy must not overwrite it.
     */
    private suspend fun insert(
        senderId: Uuid,
        senderName: String?,
        subject: String,
        sent: Instant,
        rawContent: ByteArray,
        textContent: String?,
        htmlContent: String?,
        isRead: Boolean,
        recipients: List<NewRecipient>,
    ): Uuid? = database.query {
        // Check and insert share this transaction. The dedup key has no unique index (the subject
        // is `text` and can blow the btree key limit), so the constraint cannot do it for us --
        // but ImporterManager keeps one importer per account, so there is no second writer.
        if (isKnown(sent, subject)) return@query null

        val emailId = Emails.insertAndGetId {
            it[imapAccount] = account.id
            it[sender] = senderId
            it[Emails.senderName] = senderName
            it[Emails.subject] = subject
            it[Emails.sent] = sent.truncatedToSecond()
            it[Emails.rawContent] = rawContent
            it[Emails.textContent] = textContent
            it[Emails.htmlContent] = htmlContent
            it[Emails.isRead] = isRead
        }.value

        // Written here rather than left to the queue: the body is parsed anyway, so the preview
        // costs nothing at this point, and a mail is in a listing the moment it is imported.
        EmailPreviews.upsert {
            it[email] = emailId
            it[preview] = mailPreview(textContent, htmlContent)
        }

        recipients
            // The unique index is (mail, address, field), so an address listed twice in the same
            // field has to collapse into one row. Sorting first lets the named entry win.
            .sortedBy { it.name == null }
            .distinctBy { it.emailUserId to it.type }
            .forEach { recipient ->
                EmailRecipients.insert {
                    it[email] = emailId
                    it[emailUser] = recipient.emailUserId
                    it[name] = recipient.name
                    it[type] = recipient.type
                }
            }

        emailId
    }

    /** Mails are recognised by account, send second and subject, see [Emails]. */
    private fun isKnown(sent: Instant, subject: String): Boolean =
        Emails
            .select(Emails.id)
            .where {
                (Emails.imapAccount eq account.id) and
                    (Emails.sent eq sent.truncatedToSecond()) and
                    (Emails.subject eq subject)
            }
            .empty()
            .not()

    private data class NewRecipient(
        val emailUserId: Uuid,
        val name: String?,
        val type: EmailRecipientType,
    )
}
