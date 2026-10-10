package es.jvbabi.overmail.server.jobs.importer

import es.jvbabi.overmail.kamel.MailFolder
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.ImapFolderCursors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.upsert
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/** How many mails a pass reads before it writes down how far it got. */
private const val CURSOR_SAVE_INTERVAL = 500

/** How many passes may fail on the same mail before it is given up on. */
private const val MAX_ATTEMPTS_PER_MAIL = 3

/**
 * Reads a folder by UID: a pass asks for everything above the UID it stopped at last time
 * ([ImapFolderCursors]), so its cost is the mail that arrived, not the size of the folder.
 *
 * A folder without a cursor, or one whose `UIDVALIDITY` changed, is read from its first mail.
 * That is safe to repeat: a mail that is already stored is recognised before its body is loaded.
 */
class FolderSynchronizerImpl(
    private val database: OvermailDatabase,
    private val pipeline: EmailImportPipeline,
) : FolderSynchronizer {

    private val logger = LoggerFactory.getLogger(FolderSynchronizerImpl::class.java)

    /** The mail a folder's passes keep failing on, see [giveUpOn]. */
    private val failures = ConcurrentHashMap<Pair<Uuid, String>, Failure>()

    private data class Failure(val uid: Long, val attempts: Int)

    override suspend fun synchronize(folder: MailFolder, context: ImportContext) {
        val account = context.account
        val key = account.id to folder.fullName

        val state = folder.status()
        val cursor = database.query { readCursor(account.id, folder.fullName) }
        val start = cursor?.takeIf { it.uidValidity == state.uidValidity }?.lastSeenUid ?: 0

        var reached = start
        var failedAt: Long? = null
        var sinceSave = 0

        suspend fun save() {
            // Not past a mail that failed: the next pass has to see it again.
            val position = failedAt?.let { minOf(reached, it - 1) } ?: reached
            if (cursor?.uidValidity == state.uidValidity && cursor.lastSeenUid == position) return
            database.query { writeCursor(account.id, folder.fullName, state.uidValidity, position) }
        }

        try {
            // The server says where its next UID starts, so "nothing new" costs no fetch.
            if (state.uidNext?.let { start + 1 >= it } == true) return

            folder.getMails {
                uidRange(start + 1)
                envelope = true
                flags = true
                uid = true
                onUnreadable { mail ->
                    logger.error("Mail ${mail.uid ?: "#${mail.sequenceNumber}"} of ${folder.fullName} (${account.authentication.username}) cannot be read, skipping it", mail.cause)
                    mail.uid?.let { reached = maxOf(reached, it) }
                }
            }.collect { mail ->
                val uid = mail.uid.await()
                try {
                    val result = pipeline.import(mail, context)
                    if (result is EmailInserter.Result.Rejected) {
                        logger.warn("Skipping mail $uid of ${folder.fullName} (${account.authentication.username}): ${result.reason}")
                    }
                    failures.remove(key)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!giveUpOn(key, uid)) {
                        failedAt = uid
                        throw e
                    }
                    logger.error("Giving up on mail $uid of ${folder.fullName} (${account.authentication.username}) after $MAX_ATTEMPTS_PER_MAIL attempts", e)
                }
                reached = maxOf(reached, uid)
                if (++sinceSave >= CURSOR_SAVE_INTERVAL) {
                    sinceSave = 0
                    save()
                }
            }
        } finally {
            withContext(NonCancellable) { save() }
        }
    }

    /**
     * Whether the mail [uid] has failed often enough to be passed over.
     *
     * A failure stops the pass, because it is usually the connection or the database and the mail
     * after it would fail the same way. A mail that fails every time would hold up its folder for
     * good though, so after [MAX_ATTEMPTS_PER_MAIL] passes it is left behind.
     */
    private fun giveUpOn(key: Pair<Uuid, String>, uid: Long): Boolean {
        val failure = failures.compute(key) { _, last ->
            if (last?.uid == uid) last.copy(attempts = last.attempts + 1) else Failure(uid, 1)
        }!!
        if (failure.attempts < MAX_ATTEMPTS_PER_MAIL) return false
        failures.remove(key)
        return true
    }

    private data class Cursor(val uidValidity: Long, val lastSeenUid: Long)

    private fun readCursor(accountId: Uuid, folder: String): Cursor? =
        ImapFolderCursors
            .select(ImapFolderCursors.uidValidity, ImapFolderCursors.lastSeenUid)
            .where { (ImapFolderCursors.imapAccount eq accountId) and (ImapFolderCursors.folder eq folder) }
            .firstOrNull()
            ?.let { Cursor(it[ImapFolderCursors.uidValidity], it[ImapFolderCursors.lastSeenUid]) }

    private fun writeCursor(accountId: Uuid, folder: String, uidValidity: Long, lastSeenUid: Long) {
        ImapFolderCursors.upsert {
            it[imapAccount] = accountId
            it[ImapFolderCursors.folder] = folder
            it[ImapFolderCursors.uidValidity] = uidValidity
            it[ImapFolderCursors.lastSeenUid] = lastSeenUid
        }
    }
}
