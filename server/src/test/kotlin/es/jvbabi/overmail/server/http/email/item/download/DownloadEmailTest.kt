package es.jvbabi.overmail.server.http.email.item.download

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.EmailUser
import es.jvbabi.overmail.server.database.models.ImapAccount
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.http.api.installApiErrorHandling
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.di.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** The mail's own source, handed out as a file. */
class DownloadEmailTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:download-email;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private lateinit var signedIn: User
    private lateinit var stranger: User

    private val source = "From: sender@example.com\r\nSubject: Rechnung\r\n\r\nHallo.\r\n".toByteArray()

    /** A fixed send time, so the date the filename carries is one the assertions can name. */
    private val sentAt = LocalDate(2026, 3, 9).atTime(14, 30).toInstant(TimeZone.currentSystemDefault())
    private val day = "2026-03-09"

    @Test
    fun `the stored source comes back byte for byte, as an attachment`() = testApplication {
        val mail = setUp("Rechnung Mai")
        installRoute()

        val response = client.get("/api/emails/$mail/download")

        assertEquals(HttpStatusCode.OK, response.status)
        assertContentEquals(source, response.readRawBytes())
        assertEquals("message/rfc822", response.headers[HttpHeaders.ContentType])

        val disposition = response.headers[HttpHeaders.ContentDisposition]!!
        assertTrue(disposition.startsWith("attachment"), disposition)
        assertTrue(disposition.contains("""filename="${day}_Rechnung Mai.eml""""), disposition)
    }

    @Test
    fun `a subject that cannot be a filename is cut down to one`() = testApplication {
        // Slashes, quotes and umlauts all have to go: this ends up in a header and on a disk.
        val mail = setUp("""../../etc/passwd "Grüße"""")
        installRoute()

        val disposition = client.get("/api/emails/$mail/download")
            .headers[HttpHeaders.ContentDisposition]!!

        val fileName = Regex("""filename="([^"]*)"""").find(disposition)!!.groupValues[1]
        assertEquals("${day}_etc_passwd _Gr__e.eml", fileName)
    }

    @Test
    fun `a subject with nothing usable in it still names a file`() = testApplication {
        val mail = setUp("«»")
        installRoute()

        val disposition = client.get("/api/emails/$mail/download")
            .headers[HttpHeaders.ContentDisposition]!!

        assertTrue(disposition.contains("""filename="${day}_email.eml""""), disposition)
    }

    @Test
    fun `a mail of somebody else is not handed out`() = testApplication {
        setUp("Mine")
        installRoute()

        val theirs = database.query {
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
                sent = sentAt
                rawContent = source
            }.id.value
        }

        assertEquals(HttpStatusCode.Forbidden, client.get("/api/emails/$theirs/download").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/emails/${Uuid.random()}/download").status)
    }

    /** A signed-in user with one mail, plus somebody else to be refused. */
    private suspend fun setUp(subject: String): Uuid {
        database.init()
        return database.query {
            signedIn = User.new {
                username = "owner-${Uuid.random()}"
                email = "owner-${Uuid.random()}@example.com"
                firstname = "Julius"
                lastname = "Babies"
            }
            stranger = User.new {
                username = "stranger-${Uuid.random()}"
                email = "stranger-${Uuid.random()}@example.com"
                firstname = "Some"
                lastname = "One"
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
            Email.new {
                imapAccount = account
                this.sender = sender
                senderName = "The Sender"
                this.subject = subject
                sent = sentAt
                rawContent = source
            }.id.value
        }
    }

    private fun ApplicationTestBuilder.installRoute() {
        application {
            install(ContentNegotiation) { json() }
            installApiErrorHandling()
            install(Authentication) { alwaysSignedIn() }
            dependencies {
                provide<OvermailDatabase> { database }
            }
            routing {
                route("/api/emails/{emailId}/download") { downloadEmail() }
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
