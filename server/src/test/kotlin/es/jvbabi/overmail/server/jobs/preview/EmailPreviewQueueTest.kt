package es.jvbabi.overmail.server.jobs.preview

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.EmailPreviews
import es.jvbabi.overmail.server.database.models.EmailUser
import es.jvbabi.overmail.server.database.models.ImapAccount
import es.jvbabi.overmail.server.database.models.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** The queue that works out the previews of mails that were stored without one. */
class EmailPreviewQueueTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:preview-queue;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private lateinit var signedIn: User
    private lateinit var account: ImapAccount
    private lateinit var sender: EmailUser

    @Test
    fun `the backfill reaches every mail without a preview`() = runBlocking {
        setUp()
        val plain = addMail(text = "Die Rechnung liegt bei.", html = null)
        val markup = addMail(text = null, html = "<p>Ihre Bestellung ist unterwegs.</p>")
        val silent = addMail(text = null, html = null)

        val queue = EmailPreviewQueue(database)
        val consumer = CoroutineScope(Dispatchers.Default).launch { queue.consume() }
        queue.backfill()

        assertEquals("Die Rechnung liegt bei.", awaitPreview(plain))
        assertEquals("Ihre Bestellung ist unterwegs.", awaitPreview(markup))
        // A mail with nothing readable gets a row all the same, or the backfill would find it
        // again on every start.
        assertEquals("", awaitPreview(silent))

        consumer.cancel()
    }

    @Test
    fun `a mail that already has one is left alone`() = runBlocking {
        setUp()
        val mail = addMail(text = "Der Inhalt", html = null)
        database.query {
            EmailPreviews.insert {
                it[email] = mail
                it[preview] = "von Hand"
            }
        }

        val queue = EmailPreviewQueue(database)
        val consumer = CoroutineScope(Dispatchers.Default).launch { queue.consume() }
        queue.backfill()

        // Nothing was queued, so nothing overwrote it.
        assertEquals("von Hand", previewOf(mail))

        consumer.cancel()
    }

    @Test
    fun `a single mail can be handed over on its own`() = runBlocking {
        setUp()
        val mail = addMail(text = "Nur diese eine", html = null)

        val queue = EmailPreviewQueue(database)
        val consumer = CoroutineScope(Dispatchers.Default).launch { queue.consume() }
        queue.enqueue(mail)

        assertEquals("Nur diese eine", awaitPreview(mail))

        consumer.cancel()
    }

    private suspend fun awaitPreview(emailId: Uuid): String? = withTimeout(10_000) {
        while (true) {
            val preview = previewOf(emailId)
            if (preview != null) return@withTimeout preview
            yield()
        }
        null
    }

    private suspend fun previewOf(emailId: Uuid): String? = database.query {
        EmailPreviews
            .select(EmailPreviews.preview)
            .where { EmailPreviews.email eq emailId }
            .firstOrNull()
            ?.get(EmailPreviews.preview)
    }

    private suspend fun addMail(text: String?, html: String?): Uuid = database.query {
        Email.new {
            imapAccount = account
            this.sender = this@EmailPreviewQueueTest.sender
            senderName = "The Sender"
            subject = "Mail ${Uuid.random()}"
            sent = Clock.System.now()
            rawContent = ByteArray(0)
            textContent = text
            htmlContent = html
        }.id.value
    }

    private suspend fun setUp() {
        database.init()
        database.query {
            signedIn = User.new {
                username = "owner-${Uuid.random()}"
                email = "owner-${Uuid.random()}@example.com"
                firstname = "Julius"
                lastname = "Babies"
            }
            account = ImapAccount.new {
                user = signedIn
                host = "imap.example.com"
                port = 993
                username = "owner"
                password = "secret"
            }
            sender = EmailUser.new {
                user = signedIn
                address = "sender@example.com"
            }
        }
    }
}
