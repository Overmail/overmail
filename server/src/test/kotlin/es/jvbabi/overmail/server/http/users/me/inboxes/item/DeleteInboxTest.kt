package es.jvbabi.overmail.server.http.users.me.inboxes.item

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import es.jvbabi.overmail.server.ai.classification.EmailClassification
import es.jvbabi.overmail.server.ai.classification.EmailClassificationQueue
import es.jvbabi.overmail.server.config.ApplicationConfig
import es.jvbabi.overmail.server.config.EmailConfig
import es.jvbabi.overmail.server.config.SmtpConfig
import es.jvbabi.overmail.server.data.knowledge.KnowledgeStore
import es.jvbabi.overmail.server.data.notifier.MailNotifier
import es.jvbabi.overmail.server.database.DatabaseConfig
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.EmailUser
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccount
import es.jvbabi.overmail.server.database.models.ImapAccountFolderSync
import es.jvbabi.overmail.server.database.models.ImapAccountFolderSyncs
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.http.api.installApiErrorHandling
import es.jvbabi.overmail.server.jobs.importer.ImporterManager
import io.ktor.client.request.delete
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respond
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll

class DeleteInboxTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:delete-inbox;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private var signedIn: User? = null

    @Test
    fun `deleting a mailbox takes its mails and its folder settings with it`() = testApplication {
        val user = setUpUser()
        installRoute()
        val account = setUpAccount(user, mails = 3, folders = listOf("INBOX", "Sent"))

        val response = client.delete("/api/users/me/inboxes/$account")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            3,
            Json.parseToJsonElement(response.bodyAsText()).jsonObject["deleted_emails"]!!.jsonPrimitive.content.toInt(),
        )

        database.query {
            assertNull(ImapAccount.findById(account))
            // Both go by the cascades on the tables rather than by hand here.
            assertEquals(0, Emails.selectAll().where { Emails.imapAccount eq account }.count().toInt())
            assertEquals(
                0,
                ImapAccountFolderSyncs.selectAll().where { ImapAccountFolderSyncs.imapAccount eq account }.count().toInt(),
            )
        }
    }

    @Test
    fun `a mailbox that is not yours is a miss, not a refusal`() = testApplication {
        setUpUser()
        installRoute()

        val strangersAccount = database.query {
            val stranger = User.new {
                username = "stranger-${Uuid.random()}"
                email = "stranger-${Uuid.random()}@example.com"
                firstname = "Someone"
                lastname = "Else"
            }
            ImapAccount.new {
                this.user = stranger
                host = "imap.stranger.example"
                port = 993
                username = "stranger@example.com"
                password = "secret"
            }.id.value
        }

        // 404 rather than 403: a refusal would confirm the id belongs to somebody.
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/users/me/inboxes/$strangersAccount").status)
        // And it is still there.
        database.query { assertEquals(strangersAccount, ImapAccount.findById(strangersAccount)?.id?.value) }
    }

    @Test
    fun `an id that is not an id is a miss`() = testApplication {
        setUpUser()
        installRoute()

        assertEquals(HttpStatusCode.NotFound, client.delete("/api/users/me/inboxes/not-a-uuid").status)
    }

    @Test
    fun `without a session nothing is deleted`() = testApplication {
        val user = setUpUser()
        installRoute()
        val account = setUpAccount(user, mails = 1, folders = listOf("INBOX"))
        signedIn = null

        assertEquals(HttpStatusCode.Unauthorized, client.delete("/api/users/me/inboxes/$account").status)
        signedIn = user
        database.query { assertEquals(account, ImapAccount.findById(account)?.id?.value) }
    }

    private suspend fun setUpAccount(user: User, mails: Int, folders: List<String>): Uuid = database.query {
        val account = ImapAccount.new {
            this.user = user
            host = "imap.example.com"
            port = 993
            username = "julius-${Uuid.random()}@example.com"
            password = "secret"
        }
        folders.forEach { name ->
            ImapAccountFolderSync.new {
                imapAccount = account
                folder = name
                imapPush = false
                aiImport = ImapAccountFolderSync.AiImportSettings.AllMessages
            }
        }
        val sender = EmailUser.new {
            this.user = user
            address = "sender-${Uuid.random()}@example.com"
        }
        repeat(mails) { index ->
            Emails.insertAndGetId {
                it[imapAccount] = account.id
                it[Emails.sender] = sender.id
                it[subject] = "Mail $index ${Uuid.random()}"
                it[sent] = Instant.fromEpochSeconds(1_700_000_000 + index.toLong())
                it[rawContent] = ByteArray(0)
                it[isRead] = false
            }
        }
        account.id.value
    }

    private suspend fun setUpUser(): User {
        database.init()
        return database.query {
            User.new {
                username = "owner-${Uuid.random()}"
                email = "owner-${Uuid.random()}@example.com"
                firstname = "Julius"
                lastname = "Babies"
            }
        }.also { signedIn = it }
    }

    private val testModel = LLModel(
        provider = LLMProvider.OpenAI,
        id = "test-model",
        capabilities = listOf(LLMCapability.Completion),
    )

    /** Nothing in here is reached: the manager runs on a scope that is already over. */
    private val testConfig = ApplicationConfig(
        baseUrl = "http://localhost",
        database = DatabaseConfig(host = "localhost", database = "none", user = "none", password = "none"),
        email = EmailConfig(
            smtp = SmtpConfig(host = "localhost", port = 25, auth = SmtpConfig.Auth(username = "none", password = "none"))
        ),
        ai = ApplicationConfig.AiConfig(apiKey = "none", model = "test-model", baseUrl = "http://localhost:1"),
    )

    private fun ApplicationTestBuilder.installRoute() {
        application {
            install(ContentNegotiation) { json() }
            installApiErrorHandling()
            install(Authentication) { session() }
            dependencies {
                provide<OvermailDatabase> { database }
                provide<ImporterManager> {
                    ImporterManager(
                        database = database,
                        coroutineScope = CoroutineScope(Job()).also { it.cancel() },
                        emailClassificationQueue = EmailClassificationQueue(
                            emailClassification = EmailClassification(
                                config = testConfig,
                                model = testModel,
                                overmailDatabase = database,
                                mailNotifier = MailNotifier(),
                                knowledgeStore = KnowledgeStore(database),
                            ),
                            database = database,
                        ),
                        mailNotifier = MailNotifier(),
                    )
                }
            }
            routing {
                route("/api/users/me/inboxes/{inboxId}") { deleteInbox() }
            }
        }
    }

    private fun AuthenticationConfig.session() =
        register(object : AuthenticationProvider(TestConfig()) {
            override suspend fun onAuthenticate(context: AuthenticationContext) {
                val user = signedIn
                if (user == null) {
                    context.challenge("test", AuthenticationFailedCause.NoCredentials) { challenge, call ->
                        call.respond(HttpStatusCode.Unauthorized)
                        challenge.complete()
                    }
                    return
                }
                context.principal(user)
            }
        })

    private class TestConfig : AuthenticationProvider.Config(null)
}
