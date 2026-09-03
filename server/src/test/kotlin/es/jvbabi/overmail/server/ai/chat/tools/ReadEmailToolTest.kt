package es.jvbabi.overmail.server.ai.chat.tools

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.EmailRecipient
import es.jvbabi.overmail.server.database.models.EmailRecipientType
import es.jvbabi.overmail.server.database.models.EmailUser
import es.jvbabi.overmail.server.database.models.ImapAccount
import es.jvbabi.overmail.server.database.models.User
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.uuid.Uuid

class ReadEmailToolTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:read-email-tool;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    /** One mail owned by one user, plus a second user who has nothing to do with it. */
    private suspend fun setUp(): Fixture {
        database.init()
        return database.query {
            val owner = User.new {
                username = "owner-${Uuid.random()}"
                email = "owner-${Uuid.random()}@example.com"
            }
            val stranger = User.new {
                username = "stranger-${Uuid.random()}"
                email = "stranger-${Uuid.random()}@example.com"
            }
            val account = ImapAccount.new {
                user = owner
                host = "imap.example.com"
                port = 993
                username = "owner"
                password = "secret"
            }
            val sender = EmailUser.new {
                user = owner
                address = "sender@example.com"
            }
            val recipient = EmailUser.new {
                user = owner
                address = "owner@example.com"
            }
            val email = Email.new {
                imapAccount = account
                this.sender = sender
                senderName = "The Sender"
                subject = "Invoice 42"
                sent = Clock.System.now()
                rawContent = ByteArray(0)
                textContent = "Please pay 42 EUR."
            }
            EmailRecipient.new {
                this.email = email
                emailUser = recipient
                name = "The Owner"
                type = EmailRecipientType.RECIPIENT
            }

            Fixture(ownerId = owner.id.value, strangerId = stranger.id.value, emailId = email.id.value)
        }
    }

    @Test
    fun `reads a mail of the user`() = runTest {
        val fixture = setUp()
        val tool = ReadEmailTool(userId = fixture.ownerId, database = database)

        val result = tool.execute(ReadEmailTool.Args(emailId = fixture.emailId.toString()))

        val email = assertIs<ReadEmailTool.Result.Email>(result)
        assertEquals("Invoice 42", email.subject)
        assertEquals("sender@example.com", email.senderAddress)
        assertEquals("The Sender", email.senderName)
        assertEquals("Please pay 42 EUR.", email.body)
        assertFalse(email.bodyTruncated)
        assertEquals(
            listOf(ReadEmailTool.Recipient("owner@example.com", "The Owner", EmailRecipientType.RECIPIENT)),
            email.recipients,
        )
    }

    @Test
    fun `reports the mail it read as markup`() = runTest {
        val fixture = setUp()
        val markup = mutableListOf<String>()
        val tool = ReadEmailTool(userId = fixture.ownerId, database = database, onEmailRead = markup::add)

        tool.execute(ReadEmailTool.Args(emailId = fixture.emailId.toString()))
        // Nothing to show for a mail that was not read.
        tool.execute(ReadEmailTool.Args(emailId = Uuid.random().toString()))

        assertEquals(
            listOf("""<toolcall-read-email emailId="${fixture.emailId}" avatarUrl="" subject="Invoice 42"></toolcall-read-email>"""),
            markup,
        )
    }

    @Test
    fun `escapes the subject it puts into the markup`() {
        val id = Uuid.random()
        val markup = ReadEmailTool.markup(id, subject = """Re: "5 < 6" & more""", avatarUrl = null)

        assertEquals(
            """<toolcall-read-email emailId="$id" avatarUrl="" subject="Re: &quot;5 &lt; 6&quot; &amp; more"></toolcall-read-email>""",
            markup,
        )
    }

    @Test
    fun `does not read a mail of another user`() = runTest {
        val fixture = setUp()
        val tool = ReadEmailTool(userId = fixture.strangerId, database = database)

        val result = tool.execute(ReadEmailTool.Args(emailId = fixture.emailId.toString()))

        assertIs<ReadEmailTool.Result.NotFound>(result)
    }

    @Test
    fun `reports unknown and malformed ids as not found`() = runTest {
        val fixture = setUp()
        val tool = ReadEmailTool(userId = fixture.ownerId, database = database)

        assertIs<ReadEmailTool.Result.NotFound>(
            tool.execute(ReadEmailTool.Args(emailId = Uuid.random().toString()))
        )
        assertIs<ReadEmailTool.Result.NotFound>(
            tool.execute(ReadEmailTool.Args(emailId = "not-a-uuid"))
        )
    }

    private data class Fixture(
        val ownerId: User.Id,
        val strangerId: User.Id,
        val emailId: Email.Id,
    )
}
