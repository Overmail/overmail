package es.jvbabi.overmail.server.oauth

import es.jvbabi.overmail.server.config.OAuthClientConfig
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.ImapAccount
import es.jvbabi.overmail.server.database.models.OAuthGrant
import es.jvbabi.overmail.server.database.models.OAuthGrants
import es.jvbabi.authentikt.core.step.plugins.builtin.OIDCTokens
import es.jvbabi.overmail.server.database.models.User
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val CLIENT_ID = "the-client"
private const val CLIENT_SECRET = "the-secret"

class OAuthTokensTest {

    private val database = OvermailDatabase(
        // One per test: the renewal walks every grant there is.
        Database.connect("jdbc:h2:mem:oauth-tokens-${Uuid.random()};DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private var now = Instant.parse("2026-10-07T12:00:00Z")
    private val clock = object : Clock {
        override fun now() = this@OAuthTokensTest.now
    }

    /** The forms the fake token endpoint received, in order. */
    private val tokenRequests = mutableListOf<Map<String, String>>()

    /** Whether the fake provider refuses every renewal, as for a grant the user revoked. */
    private var refuse = false

    /** Whether the fake provider is down and answers every renewal with a 503. */
    private var down = false

    private lateinit var provider: EmbeddedServer<*, *>
    private lateinit var tokens: OAuthTokens

    @BeforeTest
    fun start() {
        provider = embeddedServer(Netty, port = 0) {
            routing {
                post("/token") {
                    val form = call.receiveParameters()
                    tokenRequests += form.entries().associate { it.key to it.value.single() }
                    if (down) {
                        call.respondText("unavailable", ContentType.Text.Plain, HttpStatusCode.ServiceUnavailable)
                    } else if (refuse) {
                        call.respondText(
                            """{"error":"invalid_grant","error_description":"The grant was revoked"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.BadRequest,
                        )
                    } else {
                        val n = tokenRequests.size
                        call.respondText(
                            """{"access_token":"access-$n","token_type":"Bearer","expires_in":3600,"refresh_token":"refresh-$n"}""",
                            ContentType.Application.Json,
                        )
                    }
                }
            }
        }.start(wait = false)
        val port = runBlocking { provider.engine.resolvedConnectors().first().port }

        val providers = OAuthProviders(
            clients = mapOf("microsoft" to OAuthClientConfig(clientId = CLIENT_ID, clientSecret = CLIENT_SECRET)),
            baseUrl = "https://overmail.example",
            endpoints = {
                OAuthEndpoints(
                    authorize = "https://login.example/authorize",
                    token = "http://127.0.0.1:$port/token",
                    jwks = "http://127.0.0.1:$port/keys",
                    issuer = null,
                )
            },
        )
        tokens = OAuthTokens(database, providers, clock = clock)
    }

    @AfterTest
    fun stop() {
        provider.stop(0, 0)
    }

    @Test
    fun `an hour's token is renewed halfway through its life`() = runBlocking {
        val grantId = grant(lifetime = 1.hours)

        now += 29.minutes
        tokens.renewDue()
        assertTrue(tokenRequests.isEmpty())

        now += 2.minutes
        tokens.renewDue()
        val request = assertNotNull(tokenRequests.singleOrNull())
        assertEquals("refresh_token", request["grant_type"])
        assertEquals("refresh-0", request["refresh_token"])
        assertEquals(CLIENT_ID, request["client_id"])
        assertEquals(CLIENT_SECRET, request["client_secret"])
        assertTrue("https://outlook.office.com/IMAP.AccessAsUser.All" in request["scope"]!!.split(" "))

        val renewed = database.query { OAuthGrant[grantId].let { Triple(it.accessToken, it.refreshToken, it.accessTokenExpiresAt) } }
        // The new refresh token replaces the old one, which Microsoft no longer takes.
        assertEquals(Triple("access-1", "refresh-1", now + 3600.seconds), renewed)

        // Not due again until halfway through the new one.
        tokens.renewDue()
        assertEquals(1, tokenRequests.size)
    }

    @Test
    fun `a long-lived token is renewed an hour before it runs out`() = runBlocking {
        grant(lifetime = 1.days)

        now += 22.hours
        tokens.renewDue()
        assertTrue(tokenRequests.isEmpty())

        now += 1.hours + 1.minutes
        tokens.renewDue()
        assertEquals(1, tokenRequests.size)
    }

    @Test
    fun `a token about to run out is renewed before it is handed out`() = runBlocking {
        val grantId = grant(lifetime = 1.hours)

        now += 10.minutes
        assertEquals("access-0", tokens.accessToken(grantId))
        assertTrue(tokenRequests.isEmpty())

        now += 49.minutes
        assertEquals("access-1", tokens.accessToken(grantId))
        assertEquals(1, tokenRequests.size)
    }

    @Test
    fun `a renewal that failed is not retried straight away`() = runBlocking {
        val grantId = grant(lifetime = 1.hours)
        down = true

        // About to run out, so the connection asking for it tries to renew it.
        now += 59.minutes
        assertEquals("access-0", tokens.accessToken(grantId))
        assertEquals(1, tokenRequests.size)
        assertNotNull(database.query { OAuthGrant[grantId].renewalFailedAt })

        // Neither the next connection nor the job asks again within the backoff.
        now += 1.minutes
        assertEquals("access-0", tokens.accessToken(grantId))
        tokens.renewDue()
        assertEquals(1, tokenRequests.size)

        // A provider that was down is no reason for a new sign-in.
        assertEquals(false, database.query { OAuthGrant[grantId].requiresReauthentication })

        down = false
        now += 15.minutes
        tokens.renewDue()
        assertEquals(2, tokenRequests.size)
        assertNull(database.query { OAuthGrant[grantId].renewalFailedAt })
    }

    @Test
    fun `a renewed inbox is reported, an onboarding and a failed renewal are not`() = runBlocking {
        val inboxGrant = grant(lifetime = 1.hours, withInbox = true)
        grant(lifetime = 1.hours)
        val inboxId = database.query { OAuthGrant[inboxGrant].readValues[OAuthGrants.imapAccount]!!.value }
        val renewed = mutableListOf<Uuid>()

        // Not due yet: nothing is renewed, so nothing is restarted.
        tokens.renewDue { renewed += it }
        assertTrue(renewed.isEmpty())

        now += 31.minutes
        tokens.renewDue { renewed += it }
        assertEquals(2, tokenRequests.size)
        // What the importer is restarted for; the onboarding has none.
        assertEquals(listOf(inboxId), renewed)

        down = true
        now += 31.minutes
        tokens.renewDue { renewed += it }
        assertEquals(listOf(inboxId), renewed)
    }

    @Test
    fun `a grant the provider refuses is locked until a new sign-in`() = runBlocking {
        val grantId = grant(lifetime = 1.hours, withInbox = true)
        refuse = true

        now += 31.minutes
        tokens.renewDue()
        assertEquals(true, database.query { OAuthGrant[grantId].requiresReauthentication })

        refuse = false
        now += 1.days
        tokens.renewDue()
        assertEquals("access-0", tokens.accessToken(grantId))
        assertEquals(1, tokenRequests.size)
    }

    @Test
    fun `signing in again to a locked inbox puts the new tokens on its grant`() = runBlocking {
        val grantId = grant(lifetime = 1.hours, withInbox = true)
        val (userId, inboxId) = database.query {
            OAuthGrant[grantId].apply { requiresReauthentication = true }
                .let { it.readValues[OAuthGrants.user].value to it.readValues[OAuthGrants.imapAccount]!!.value }
        }

        val reauthenticated = tokens.storeOnboarding("flow-again", userId, mailbox("fresh-access", "fresh-refresh"))

        assertEquals(inboxId, reauthenticated)
        val grant = database.query {
            OAuthGrant[grantId].let { listOf(it.accessToken, it.refreshToken, it.requiresReauthentication, it.onboardingId) }
        }
        assertEquals(listOf("fresh-access", "fresh-refresh", false, "flow-again"), grant)
        assertEquals(1, database.query { OAuthGrant.all().count() })
    }

    @Test
    fun `signing in again to an inbox that is fine starts a new onboarding`() = runBlocking {
        val grantId = grant(lifetime = 1.hours, withInbox = true)
        val userId = database.query { OAuthGrant[grantId].readValues[OAuthGrants.user].value }

        // Submitting it is the conflict it always was; nothing is taken over here.
        assertNull(tokens.storeOnboarding("flow-again", userId, mailbox("fresh-access", "fresh-refresh")))
        assertEquals("access-0", database.query { OAuthGrant[grantId].accessToken })
        assertEquals(2, database.query { OAuthGrant.all().count() })
    }

    @Test
    fun `a grant without a refresh token is left alone`() = runBlocking {
        val grantId = grant(lifetime = 1.hours, refreshToken = null)

        now += 59.minutes
        tokens.renewDue()
        assertEquals("access-0", tokens.accessToken(grantId))
        assertTrue(tokenRequests.isEmpty())
    }

    @Test
    fun `an onboarding nobody finished is dropped after a day`() = runBlocking {
        val abandoned = grant(lifetime = 1.hours)
        val submitted = grant(lifetime = 1.hours, withInbox = true)

        now += 23.hours
        val fresh = grant(lifetime = 1.hours)
        now += 2.hours
        tokens.dropAbandonedOnboardings()

        val left = database.query { listOf(abandoned, submitted, fresh).map { OAuthGrant.findById(it) != null } }
        assertEquals(listOf(false, true, true), left)
    }

    private fun mailbox(accessToken: String, refreshToken: String) = OAuthMailbox(
        provider = OAuthProvider.MICROSOFT,
        address = "julius@outlook.example",
        tokens = OIDCTokens(
            accessToken = accessToken,
            tokenType = "Bearer",
            refreshToken = refreshToken,
            idToken = null,
            expiresIn = 1.hours,
            scopes = null,
            receivedAt = now,
            raw = JsonObject(emptyMap()),
        ),
    )

    private suspend fun grant(lifetime: Duration, refreshToken: String? = "refresh-0", withInbox: Boolean = false): Uuid {
        database.init()
        return database.query {
            val user = User.new {
                username = "owner-${Uuid.random()}"
                email = "owner-${Uuid.random()}@example.com"
                firstname = "Julius"
                lastname = "Babies"
            }
            val inbox = if (withInbox) ImapAccount.new {
                this.user = user
                host = "outlook.office365.com"
                port = 993
                username = "julius@outlook.example"
                password = ""
            } else null
            OAuthGrant.new {
                this.user = user
                imapAccount = inbox
                onboardingId = if (inbox == null) "flow-${Uuid.random()}" else null
                provider = "microsoft"
                address = "julius@outlook.example"
                accessToken = "access-0"
                accessTokenExpiresAt = now + lifetime
                this.refreshToken = refreshToken
                renewedAt = now
                createdAt = now
            }.id.value
        }
    }
}
