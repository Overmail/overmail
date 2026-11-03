package es.jvbabi.overmail.server.jobs.reimport

import es.jvbabi.overmail.server.data.notifier.MailNotifier
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.jobs.importer.EmailInserter
import es.jvbabi.overmail.server.jobs.importer.EmailInserterImpl
import es.jvbabi.overmail.server.jobs.importer.EmailPreviewGeneratorImpl
import es.jvbabi.overmail.server.jobs.importer.FakeClient
import es.jvbabi.overmail.server.jobs.importer.FakeFolder
import es.jvbabi.overmail.server.jobs.importer.addAccount
import es.jvbabi.overmail.server.jobs.importer.mail
import es.jvbabi.overmail.server.jobs.importer.testDatabase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EmailReimporterTest {

    private val database = testDatabase("reimporter")

    private fun reimporter(vararg folders: FakeFolder) = EmailReimporter(
        database = database,
        previewGenerator = EmailPreviewGeneratorImpl(database),
        mailNotifier = MailNotifier(),
        connect = { FakeClient(*folders) },
    )

    /** Stores [mail] the way the importer did, and answers its id. */
    private suspend fun stored(mail: es.jvbabi.overmail.kamel.Email): Email.Id {
        val account = database.addAccount()
        val result = EmailInserterImpl(database).importEmailIntoDatabase(mail, account)
        return assertIs<EmailInserter.Result.Imported>(result).email.id.value
    }

    @Test
    fun `the mail is found in any folder and its content replaced in place`() = runBlocking {
        val id = stored(mail(7, body = "Garbled."))
        val inbox = FakeFolder("INBOX", mails = listOf(mail(1), mail(2)))
        val archive = FakeFolder("Archive", mails = listOf(mail(3), mail(7, body = "The whole mail.")))

        val result = reimporter(inbox, archive).reimport(id)

        assertEquals(EmailReimporter.Result.Reimported("Archive"), result)
        database.query {
            val email = Email[id]
            assertEquals("The whole mail.", email.textContent?.trim())
            assertEquals("Mail 7", email.subject)
            // Replaced, not added to: the recipients are there once.
            assertEquals(2, email.recipients.count())
        }
    }

    @Test
    fun `a mail that no folder holds is left as it is`() = runBlocking {
        val id = stored(mail(7, body = "Garbled."))

        val result = reimporter(FakeFolder("INBOX", mails = listOf(mail(1)))).reimport(id)

        assertEquals(EmailReimporter.Result.NotFound, result)
        database.query { assertTrue(Email[id].textContent!!.contains("Garbled.")) }
    }

    @Test
    fun `a mail with the same subject at another second is not taken for it`() = runBlocking {
        val id = stored(mail(7))
        val other = mail(8, subject = "Mail 7")

        assertEquals(EmailReimporter.Result.NotFound, reimporter(FakeFolder(mails = listOf(other))).reimport(id))
    }

    @Test
    fun `a deleted mail is gone`() = runBlocking {
        database.addAccount()
        assertEquals(EmailReimporter.Result.Gone, reimporter().reimport(kotlin.uuid.Uuid.random()))
    }
}
