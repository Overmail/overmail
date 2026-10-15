package es.jvbabi.overmail.server.jobs.importer

import es.jvbabi.overmail.server.data.notifier.MailNotifier
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.ImapAccountFolderSync
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Where a mail came from: the account, the folder with what the user set for it, and what made
 * the folder due.
 */
data class ImportContext(
    val account: ImapConnection,
    val folder: ImapConnection.FolderSync,
    val trigger: ImportTrigger = ImportTrigger.Poll,
) {
    companion object {
        /**
         * A mail somebody handed in as a file, for [account]. It belongs to no folder, so it gets
         * one that says what an upload is: the assistant reads every one -- nobody uploads a mail
         * to have it left alone -- and none of them is news, because the user is holding it
         * already. The folder being added at the end of time is what keeps [PushStep] quiet.
         */
        fun upload(account: ImapConnection) = ImportContext(
            account = account,
            folder = ImapConnection.FolderSync(
                folder = "(upload)",
                imapPush = false,
                aiImportSettings = ImapAccountFolderSync.AiImportSettings.AllMessages,
                createdAt = Instant.DISTANT_FUTURE,
            ),
            trigger = ImportTrigger.Upload,
        )
    }
}

/**
 * Why a folder is looked at, see [MailboxImporter], or that no folder was: [Upload]. Changes nothing
 * about how it is read.
 */
enum class ImportTrigger(val key: String) {
    /** The poll timer fired. */
    Poll("poll"),

    /** An `IDLE` was issued: nothing reported what changed while none was held. */
    IdleStarted("idle_started"),

    /** The server reported a change on a held `IDLE`. */
    IdleChange("idle_change"),

    /** A mail was handed to the api as a file, see [ImportContext.upload]. */
    Upload("upload"),
}

/**
 * Something that follows from a mail having been stored, see [EmailImportPipeline].
 *
 * [email] is out of its transaction: its own columns are readable, its references are not.
 */
fun interface ImportStep {
    suspend fun run(email: Email, context: ImportContext)
}

/** A mail is in a listing the moment it is imported, so its preview is not left to the queue. */
class GeneratePreviewStep(private val generator: EmailPreviewGenerator) : ImportStep {
    override suspend fun run(email: Email, context: ImportContext) = generator.generatePreview(email)
}

/**
 * Hands the mail to the assistant, if the folder's setting covers it. Every mail is imported
 * either way; this only decides what the assistant is paid to read.
 */
class ClassifyStep(private val enqueue: (Email.Id) -> Unit) : ImportStep {
    override suspend fun run(email: Email, context: ImportContext) {
        if (context.folder.wantsAssistant(email.sent)) enqueue(email.id.value)
    }
}

/** Tells whoever shows or counts mails that there is one more. */
class NotifyStep(private val mailNotifier: MailNotifier) : ImportStep {
    override suspend fun run(email: Email, context: ImportContext) {
        // A mail that was not there before: every listing is one longer and one row further down.
        mailNotifier.notifyMailChanged(context.account.userId, email.id.value, movedListings = true)
    }
}

/**
 * Pushes a mail that is news to its owner's devices: one nobody has read yet, that arrived after
 * the folder was added.
 *
 * Both matter. A mail read elsewhere before the import saw it needs no notification, and a folder
 * that is read for the first time is history -- a mailbox with a thousand unread mails in it
 * must not turn into a thousand pushes.
 */
class PushStep(private val push: (userId: Uuid, emailId: Uuid, imapAccountId: Uuid) -> Unit) : ImportStep {
    override suspend fun run(email: Email, context: ImportContext) {
        if (email.isRead || email.sent < context.folder.createdAt) return
        push(context.account.userId, email.id.value, context.account.id)
    }
}
