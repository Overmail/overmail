package es.jvbabi.overmail.server.jobs.importer

import es.jvbabi.overmail.server.database.models.EmailRecipientType
import es.jvbabi.overmail.server.database.models.EmailUsers
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.jobs.importer.EmailInserter.Result
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import es.jvbabi.overmail.kamel.Email as KamelEmail

/** The step that stores a mail. */
class EmailInserterImplTest {

    private val database = testDatabase("inserter")
    private val inserter = EmailInserterImpl(database)

    @Test
    fun `a new mail is stored with its sender, recipients and read flag`() = runBlocking {
        val account = database.addAccount()

        val result = inserter.importEmailIntoDatabase(mail(1, flags = setOf(KamelEmail.Flag.Seen)), account)

        val email = assertIs<Result.Imported>(result).email
        assertEquals("Mail 1", email.subject)
        assertEquals("Ada Lovelace", email.senderName)
        assertEquals("Hello from mail 1.", email.textContent?.trim())
        assertTrue(email.isRead)

        database.query {
            val stored = es.jvbabi.overmail.server.database.models.Email[email.id]
            assertEquals("ada@example.com", stored.sender.address)
            // julius@example.com is in To twice: one row, and the named entry wins.
            val recipients = stored.recipients.map { Triple(it.emailUser.address, it.name, it.type) }.toSet()
            assertEquals(
                setOf(
                    Triple("julius@example.com", "Julius", EmailRecipientType.RECIPIENT),
                    Triple("grace@example.com", "Grace Hopper", EmailRecipientType.CC),
                ),
                recipients,
            )
        }
    }

    @Test
    fun `a mail without the seen flag is unread`() = runBlocking {
        val account = database.addAccount()

        val result = inserter.importEmailIntoDatabase(mail(1), account)

        assertFalse(assertIs<Result.Imported>(result).email.isRead)
    }

    @Test
    fun `the same mail is stored once`() = runBlocking {
        val account = database.addAccount()

        assertIs<Result.Imported>(inserter.importEmailIntoDatabase(mail(1), account))
        assertEquals(Result.AlreadyExists, inserter.importEmailIntoDatabase(mail(1), account))

        assertEquals(1, database.query { Emails.selectAll().count() })
    }

    @Test
    fun `a mail without a subject is recognised again`() = runBlocking {
        val account = database.addAccount()

        assertIs<Result.Imported>(inserter.importEmailIntoDatabase(mail(1, subject = null), account))
        assertEquals(Result.AlreadyExists, inserter.importEmailIntoDatabase(mail(1, subject = null), account))
    }

    @Test
    fun `the same mail in another account is a mail of its own`() = runBlocking {
        val first = database.addAccount()
        val second = database.addAccount()

        assertIs<Result.Imported>(inserter.importEmailIntoDatabase(mail(1), first))
        assertIs<Result.Imported>(inserter.importEmailIntoDatabase(mail(1), second))
    }

    @Test
    fun `a mail without a sender or a date is rejected and leaves nothing behind`() = runBlocking {
        val account = database.addAccount()

        assertIs<Result.Rejected>(inserter.importEmailIntoDatabase(mail(1, from = null), account))
        assertIs<Result.Rejected>(inserter.importEmailIntoDatabase(mail(2, date = null), account))

        database.query {
            assertEquals(0, Emails.selectAll().count())
            assertEquals(0, EmailUsers.selectAll().count())
        }
    }
}
