package es.jvbabi.overmail.server.jobs.importer

import es.jvbabi.overmail.server.data.notifier.MailNotifier
import es.jvbabi.overmail.server.database.models.Email
import kotlin.uuid.Uuid

/** Where a mail came from: the account, and the folder with what the user set for it. */
data class ImportContext(
    val account: ImapConnection,
    val folder: ImapConnection.FolderSync,
)

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
