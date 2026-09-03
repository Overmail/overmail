package es.jvbabi.overmail.server.ai.chat

import es.jvbabi.overmail.server.ai.chat.tools.ReadEmailTool
import es.jvbabi.overmail.server.data.notifier.AiChatMessageStream
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.EmailUser
import es.jvbabi.overmail.server.database.models.ImapAccount
import es.jvbabi.overmail.server.database.models.User
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * The registry the agent runs with, built the same way here. What is under test is the wiring:
 * a tool that reads a mail has to leave its mark in the answer.
 */
class ChatToolRegistryTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:chat-tool-registry;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    @Test
    fun `a mail the agent reads is written into the stream`() = runTest {
        val fixture = setUp()
        val stream = AiChatMessageStream()
        stream.append("Ich schaue nach.")

        val registry = chatToolRegistry(userId = fixture.userId, database = database, stream = stream)
        val tool = registry.tools.filterIsInstance<ReadEmailTool>().single()

        tool.execute(ReadEmailTool.Args(emailId = fixture.emailId.toString()))

        assertEquals(
            "Ich schaue nach.\n\n"
                + """<toolcall-read-email emailId="${fixture.emailId}" avatarUrl="" subject="Invoice 42"></toolcall-read-email>""",
            stream.snapshot().content,
        )
    }

    @Test
    fun `a mail that was not read leaves nothing behind`() = runTest {
        val fixture = setUp()
        val stream = AiChatMessageStream()

        val registry = chatToolRegistry(userId = fixture.userId, database = database, stream = stream)
        val tool = registry.tools.filterIsInstance<ReadEmailTool>().single()

        tool.execute(ReadEmailTool.Args(emailId = Uuid.random().toString()))

        assertTrue(stream.snapshot().content.isEmpty())
    }

    private data class Fixture(val userId: User.Id, val emailId: Email.Id)

    private suspend fun setUp(): Fixture {
        database.init()
        return database.query {
            val user = User.new {
                username = "owner-${Uuid.random()}"
                email = "owner-${Uuid.random()}@example.com"
            }
            val account = ImapAccount.new {
                this.user = user
                host = "imap.example.com"
                port = 993
                username = "owner"
                password = "secret"
            }
            val sender = EmailUser.new {
                this.user = user
                address = "sender@example.com"
            }
            val email = Email.new {
                imapAccount = account
                this.sender = sender
                senderName = "The Sender"
                subject = "Invoice 42"
                sent = Clock.System.now()
                rawContent = ByteArray(0)
                textContent = "Please pay."
            }

            Fixture(userId = user.id.value, emailId = email.id.value)
        }
    }
}
