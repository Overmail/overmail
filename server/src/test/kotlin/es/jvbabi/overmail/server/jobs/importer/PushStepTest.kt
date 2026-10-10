package es.jvbabi.overmail.server.jobs.importer

import es.jvbabi.overmail.kamel.Email as KamelEmail
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/** Which imported mails are news to their owner. */
class PushStepTest {

    private val database = testDatabase("push-step")

    /** User, mail and mailbox of every push, in order. */
    private val pushed = mutableListOf<Triple<Uuid, Uuid, Uuid>>()

    private val pipeline = EmailImportPipelineImpl(
        EmailInserterImpl(database),
        listOf(PushStep { userId, emailId, imapAccountId -> pushed += Triple(userId, emailId, imapAccountId) }),
    )

    // The fixtures' folder was added on the first of June at noon, and their mails are sent then.
    private val afterTheFolder = "Mon, 1 Jun 2026 12:00:30 +0000"
    private val beforeTheFolder = "Sun, 31 May 2026 12:00:30 +0000"

    @Test
    fun `an unread mail that arrived after the folder was added is pushed to its owner`() = runBlocking {
        val account = database.addAccount()

        val result = pipeline.import(mail(1, date = afterTheFolder), ImportContext(account, folderSync()))

        val imported = result as EmailInserter.Result.Imported
        assertEquals(listOf(Triple(account.userId, imported.email.id.value, account.id)), pushed)
    }

    @Test
    fun `a mail that was read before the import saw it is not`() = runBlocking {
        val account = database.addAccount()

        pipeline.import(mail(1, date = afterTheFolder, flags = setOf(KamelEmail.Flag.Seen)), ImportContext(account, folderSync()))

        assertEquals(emptyList(), pushed)
    }

    @Test
    fun `what was in the folder before it was added is history`() = runBlocking {
        val account = database.addAccount()

        pipeline.import(mail(1, date = beforeTheFolder), ImportContext(account, folderSync()))

        assertEquals(emptyList(), pushed)
    }
}
