package es.jvbabi.overmail.server.http.email.bulk

import es.jvbabi.overmail.server.data.notifier.MailNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.EmailArchiveAction
import es.jvbabi.overmail.server.database.models.EmailArchives
import es.jvbabi.overmail.server.database.models.EmailUser
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccount
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.http.api.installApiErrorHandling
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** What a picked selection is written through: one request for a stretch of the mailbox. */
class BulkEmailsTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:bulk-emails;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private lateinit var signedIn: User

    @Test
    fun `a selection is marked read, and only what was unread counts as changed`() = testApplication {
        val mails = setUp(count = 3)
        installRoute()

        val first = client.bulk("read", mails)

        assertEquals(3, first.changed())
        assertEquals(listOf(true, true, true), mails.map { isRead(it) })

        // Idempotent: the same selection again is nothing left to do.
        assertEquals(0, client.bulk("read", mails).changed())
    }

    @Test
    fun `unread takes it back`() = testApplication {
        val mails = setUp(count = 2)
        installRoute()
        client.bulk("read", mails)

        assertEquals(2, client.bulk("unread", mails).changed())
        assertEquals(listOf(false, false), mails.map { isRead(it) })
    }

    @Test
    fun `a mail of somebody else is not written and not reported`() = testApplication {
        val mails = setUp(count = 1)
        installRoute()
        val stranger = strangersMail()

        val answer = client.bulk("read", mails + stranger)

        assertEquals(1, answer.changed())
        assertEquals(false, isRead(stranger))
    }

    @Test
    fun `archiving a selection appends one event each, and unarchiving brings them back`() =
        testApplication {
            val mails = setUp(count = 3)
            installRoute()

            assertEquals(3, client.bulk("archive", mails).changed())
            assertEquals(List(3) { EmailArchiveAction.Archive }, mails.map { archiveState(it) })

            // Already there: the log is a history of decisions, not of clicks.
            assertEquals(0, client.bulk("archive", mails).changed())

            assertEquals(3, client.bulk("unarchive", mails).changed())
            assertEquals(List(3) { EmailArchiveAction.Unarchive }, mails.map { archiveState(it) })
        }

    @Test
    fun `more mails than one request may carry is refused`() = testApplication {
        setUp(count = 1)
        installRoute()

        val tooMany = List(MAX_BULK_IDS + 1) { Uuid.random() }

        assertEquals(HttpStatusCode.BadRequest, client.bulkResponse("read", tooMany).status)
    }

    @Test
    fun `nothing picked is nothing written`() = testApplication {
        setUp(count = 1)
        installRoute()

        assertEquals(0, client.bulk("read", emptyList()).changed())
    }

    private suspend fun io.ktor.client.HttpClient.bulkResponse(action: String, ids: List<Uuid>) =
        post("/api/emails/bulk/$action") {
            contentType(ContentType.Application.Json)
            setBody("""{"ids":[${ids.joinToString(",") { "\"$it\"" }}]}""")
        }

    private suspend fun io.ktor.client.HttpClient.bulk(action: String, ids: List<Uuid>) =
        Json.parseToJsonElement(bulkResponse(action, ids).bodyAsText()).jsonObject

    private fun kotlinx.serialization.json.JsonObject.changed() =
        getValue("changed").jsonPrimitive.int

    private suspend fun isRead(id: Uuid): Boolean = database.query {
        Emails.select(Emails.isRead).where { Emails.id eq id }.single()[Emails.isRead]
    }

    /** Where a mail stands: its latest event, or the mailbox when it has none. */
    private suspend fun archiveState(id: Uuid): EmailArchiveAction = database.query {
        EmailArchives
            .select(EmailArchives.action)
            .where { EmailArchives.email eq id }
            .orderBy(EmailArchives.createdAt to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(EmailArchives.action)
            ?: EmailArchiveAction.Unarchive
    }

    /** A mail nobody signed in here may touch. */
    private suspend fun strangersMail(): Uuid = database.query {
        val stranger = User.new {
            username = "stranger-${Uuid.random()}"
            email = "stranger-${Uuid.random()}@example.com"
            firstname = "Some"
            lastname = "One"
        }
        val account = ImapAccount.new {
            user = stranger
            host = "imap.example.com"
            port = 993
            username = "stranger"
            password = "secret"
        }
        val sender = EmailUser.new {
            user = stranger
            address = "someone@example.com"
        }
        Email.new {
            imapAccount = account
            this.sender = sender
            senderName = null
            subject = "Not yours"
            sent = Clock.System.now()
            rawContent = ByteArray(0)
        }.id.value
    }

    /** [count] mails of the signed-in user, all unread and all in the mailbox. */
    private suspend fun setUp(count: Int): List<Uuid> {
        database.init()
        return database.query {
            signedIn = User.new {
                username = "owner-${Uuid.random()}"
                email = "owner-${Uuid.random()}@example.com"
                firstname = "Julius"
                lastname = "Babies"
            }
            val account = ImapAccount.new {
                user = signedIn
                host = "imap.example.com"
                port = 993
                username = "owner"
                password = "secret"
            }
            val sender = EmailUser.new {
                user = signedIn
                address = "sender@example.com"
            }

            (0 until count).map { index ->
                Email.new {
                    imapAccount = account
                    this.sender = sender
                    senderName = "The Sender"
                    subject = "Mail $index"
                    sent = Clock.System.now()
                    rawContent = ByteArray(0)
                }.id.value
            }
        }
    }

    private fun ApplicationTestBuilder.installRoute() {
        application {
            install(ContentNegotiation) { json() }
            installApiErrorHandling()
            install(Authentication) { alwaysSignedIn() }
            dependencies {
                provide<OvermailDatabase> { database }
                provide<MailNotifier> { MailNotifier() }
            }
            routing {
                route("/api/emails/bulk") {
                    route("/read") { setEmailsRead(isRead = true) }
                    route("/unread") { setEmailsRead(isRead = false) }
                    route("/archive") { setEmailsArchiveState(EmailArchiveAction.Archive) }
                    route("/unarchive") { setEmailsArchiveState(EmailArchiveAction.Unarchive) }
                }
            }
        }
    }

    private fun AuthenticationConfig.alwaysSignedIn() =
        register(object : AuthenticationProvider(TestConfig()) {
            override suspend fun onAuthenticate(context: AuthenticationContext) {
                context.principal(signedIn)
            }
        })

    private class TestConfig : AuthenticationProvider.Config(null)
}
