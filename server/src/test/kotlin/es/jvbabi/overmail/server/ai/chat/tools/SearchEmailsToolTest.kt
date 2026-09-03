package es.jvbabi.overmail.server.ai.chat.tools

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.EmailLabel
import es.jvbabi.overmail.server.database.models.EmailUser
import es.jvbabi.overmail.server.database.models.ImapAccount
import es.jvbabi.overmail.server.database.models.Label
import es.jvbabi.overmail.server.database.models.User
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

class SearchEmailsToolTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:search-emails-tool;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private val now = Instant.parse("2026-09-03T12:00:00Z")

    @Test
    fun `matches subject and sender loosely and combines them`() = runTest {
        val fixture = setUp()
        val tool = SearchEmailsTool(userId = fixture.ownerId, database = database)

        // "rechnug" is missing a letter, "uni" is part of the address.
        val hits = tool.search(subject = "rechnug", sender = "uni")
        assertEquals(listOf("Rechnung 42"), hits.emails.map { it.subject })

        // Both have to hold: this subject exists, but not from that sender.
        assertEquals(emptyList(), tool.search(subject = "rechnung", sender = "github").emails)
    }

    @Test
    fun `answers with metadata including labels`() = runTest {
        val fixture = setUp()
        val tool = SearchEmailsTool(userId = fixture.ownerId, database = database)

        val email = tool.search(subject = "Rechnung").emails.single()

        assertEquals("mensa@uni-potsdam.de", email.senderAddress)
        assertEquals("Mensa", email.senderName)
        assertEquals(listOf("Studium"), email.labels.map { label -> label.name })
        assertTrue(!email.isRead)
    }

    @Test
    fun `filters by label, read state and date`() = runTest {
        val fixture = setUp()
        val tool = SearchEmailsTool(userId = fixture.ownerId, database = database)

        assertEquals(listOf("Rechnung 42"), tool.search(label = "studium").emails.map { it.subject })
        assertEquals(listOf("Pull request merged"), tool.search(isRead = true).emails.map { it.subject })
        // The older mail is two days old; a search from yesterday on cannot see it.
        assertEquals(
            listOf("Rechnung 42"),
            tool.search(sentAfter = "2026-09-03").emails.map { it.subject },
        )
    }

    @Test
    fun `never leaves the mailbox of its user`() = runTest {
        val fixture = setUp()
        val tool = SearchEmailsTool(userId = fixture.strangerId, database = database)

        assertEquals(emptyList(), tool.search().emails)
    }

    @Test
    fun `a date that is not a date comes back as an invalid argument`() = runTest {
        val fixture = setUp()
        val tool = SearchEmailsTool(userId = fixture.ownerId, database = database)

        val result = tool.execute(SearchEmailsTool.Args(sentAfter = "letzte Woche"))

        assertEquals("sent_after", assertIs<SearchEmailsTool.Result.InvalidArgument>(result).argument)
    }

    @Test
    fun `reports the search it ran as markup`() = runTest {
        val fixture = setUp()
        val searches = mutableListOf<String>()
        val tool = SearchEmailsTool(userId = fixture.ownerId, database = database, onSearch = searches::add)

        tool.execute(SearchEmailsTool.Args(subject = """the "big" one""", sender = null))

        assertEquals(
            listOf("""<toolcall-search-emails subject="the &quot;big&quot; one" sender=""></toolcall-search-emails>"""),
            searches,
        )
    }

    private suspend fun SearchEmailsTool.search(
        subject: String? = null,
        sender: String? = null,
        label: String? = null,
        isRead: Boolean? = null,
        sentAfter: String? = null,
    ): SearchEmailsTool.Result.Emails = assertIs(
        execute(
            SearchEmailsTool.Args(
                subject = subject,
                sender = sender,
                label = label,
                isRead = isRead,
                sentAfter = sentAfter,
            )
        )
    )

    private data class Fixture(val ownerId: User.Id, val strangerId: User.Id)

    /** Two mails of one user, one labelled and unread, one older and read, plus a stranger. */
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
            val university = EmailUser.new {
                user = owner
                address = "mensa@uni-potsdam.de"
            }
            val github = EmailUser.new {
                user = owner
                address = "notifications@github.com"
            }

            val invoice = Email.new {
                imapAccount = account
                sender = university
                senderName = "Mensa"
                subject = "Rechnung 42"
                sent = now
                rawContent = ByteArray(0)
                textContent = "Bitte zahlen."
                isRead = false
            }
            Email.new {
                imapAccount = account
                sender = github
                senderName = "GitHub"
                subject = "Pull request merged"
                sent = now - 2.days
                rawContent = ByteArray(0)
                textContent = "Merged."
                isRead = true
            }

            val label = Label.new {
                name = "Studium"
                color = "#ffffff"
                this.owner = owner
                createdAt = Clock.System.now()
                createdByAgent = false
            }
            EmailLabel.new {
                email = invoice
                this.label = label
                labeledByAgent = false
            }

            Fixture(ownerId = owner.id.value, strangerId = stranger.id.value)
        }
    }
}
