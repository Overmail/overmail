package es.jvbabi.overmail.server.http.users.me.inboxes.create.oauth

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import es.jvbabi.authentikt.core.session.sessions
import es.jvbabi.overmail.kamel.ImapClient
import es.jvbabi.overmail.server.ai.classification.EmailClassification
import es.jvbabi.overmail.server.ai.classification.EmailClassificationQueue
import es.jvbabi.overmail.server.config.ApplicationConfig
import es.jvbabi.overmail.server.config.EmailConfig
import es.jvbabi.overmail.server.config.SmtpConfig
import es.jvbabi.overmail.server.data.knowledge.KnowledgeStore
import es.jvbabi.overmail.server.data.notifier.MailNotifier
import es.jvbabi.overmail.server.database.DatabaseConfig
import es.jvbabi.overmail.server.config.OAuthClientConfig
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.ImapAccount
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.database.models.OAuthGrant
import es.jvbabi.overmail.server.database.models.OAuthGrants
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.http.api.installApiErrorHandling
import es.jvbabi.overmail.server.http.users.me.inboxes.create.submit.oauthInboxSubmitRoute
import es.jvbabi.overmail.server.jobs.importer.EmailInserterImpl
import es.jvbabi.overmail.server.jobs.importer.legacy.LegacyImporterManager
import es.jvbabi.overmail.server.jobs.importer.legacy.oauthGrantStates
import es.jvbabi.overmail.server.jobs.importer.legacy.toConnection
import es.jvbabi.overmail.server.oauth.OAuthEndpoints
import es.jvbabi.overmail.server.oauth.OAuthProviders
import es.jvbabi.overmail.server.oauth.OAuthTokens
import es.jvbabi.overmail.server.oauth.installOAuthOnboardings
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin

private const val PROVIDERS = "/api/users/me/inboxes/create/oauth"
private const val CALLBACK = "/api/inbox-sign-in/authentikt/static/plugins/authentikt-builtin/oidc/microsoft/callback"
private const val CLIENT_ID = "the-client"
private const val CLIENT_SECRET = "the-secret"
private const val GOOD_CODE = "the-code"
private const val ACCESS_TOKEN = "the-bearer"
private const val MAILBOX = "julius@outlook.example"

class OAuthRoutesTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:oauth-routes;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private var signedIn: User? = null

    /** The forms the fake token endpoint received, in order. */
    private val tokenRequests = mutableListOf<Map<String, String>>()

    /** What the last sign-in was sent off with; the provider signs it back into the id token. */
    private var nonce: String? = null

    /** What the fake provider signs its id tokens with. The client secret, as `HS256` has it. */
    private var signingSecret = CLIENT_SECRET

    /**
     * Microsoft's token endpoint, as far as the code exchange is concerned. A real server rather
     * than `externalServices`: authentikt redeems the code with an http client of its own.
     */
    private lateinit var provider: EmbeddedServer<*, *>
    private lateinit var providers: OAuthProviders
    private val tokens by lazy { OAuthTokens(database, providers) }

    private val testModel = LLModel(
        provider = LLMProvider.OpenAI,
        id = "test-model",
        capabilities = listOf(LLMCapability.Completion),
    )

    /** Nothing in here is reached: the importer this feeds runs on a scope that is already over. */
    private val testConfig = ApplicationConfig(
        baseUrl = "http://localhost",
        database = DatabaseConfig(host = "localhost", database = "none", user = "none", password = "none"),
        email = EmailConfig(
            smtp = SmtpConfig(
                host = "localhost",
                port = 25,
                auth = SmtpConfig.Auth(username = "none", password = "none"),
            )
        ),
        ai = ApplicationConfig.AiConfig(apiKey = "none", model = "test-model", baseUrl = "http://localhost:1"),
    )

    @BeforeTest
    fun startProvider() {
        provider = embeddedServer(Netty, port = 0) {
            routing {
                post("/token") {
                    val form = call.receiveParameters()
                    tokenRequests += form.entries().associate { it.key to it.value.single() }
                    if (form["code"] == GOOD_CODE) {
                        call.respondText(
                            """{"access_token":"$ACCESS_TOKEN","token_type":"Bearer","expires_in":3599,"refresh_token":"refresh","id_token":"${idToken()}"}""",
                            ContentType.Application.Json,
                        )
                    } else {
                        call.respondText(
                            """{"error":"invalid_grant","error_description":"The code has expired"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.BadRequest,
                        )
                    }
                }
            }
        }.start(wait = false)
        val port = runBlocking { provider.engine.resolvedConnectors().first().port }

        // Microsoft has a client, Google has none, and the typo is ignored rather than fatal.
        providers = OAuthProviders(
            clients = mapOf(
                "microsoft" to OAuthClientConfig(clientId = CLIENT_ID, clientSecret = CLIENT_SECRET),
                "microsfot" to OAuthClientConfig(clientId = "typo", clientSecret = "typo"),
            ),
            baseUrl = "https://overmail.example",
            endpoints = {
                OAuthEndpoints(
                    authorize = "https://login.example/authorize",
                    token = "http://127.0.0.1:$port/token",
                    // Never fetched: an HS256 id token is checked against the client secret.
                    jwks = "http://127.0.0.1:$port/keys",
                    issuer = null,
                )
            },
        )
    }

    @AfterTest
    fun stopProvider() {
        provider.stop(0, 0)
    }

    @Test
    fun `lists only the providers with a client`() = testApplication {
        setUpUser()
        installRoutes()

        val response = client.get(PROVIDERS)
        assertEquals(HttpStatusCode.OK, response.status)

        val ids = Json.parseToJsonElement(response.bodyAsText()).jsonObject["providers"]!!.jsonArray
            .map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf("microsoft"), ids)
    }

    @Test
    fun `starting a sign-in redirects to the provider with a state for the callback`() = testApplication {
        setUpUser()
        installRoutes()
        val client = createClient { followRedirects = false }

        val response = client.get("$PROVIDERS/microsoft")
        assertEquals(HttpStatusCode.Found, response.status)

        val target = Url(response.headers[HttpHeaders.Location]!!)
        assertEquals("login.example", target.host)
        assertEquals(CLIENT_ID, target.parameters["client_id"])
        assertEquals("code", target.parameters["response_type"])
        assertEquals("https://overmail.example$CALLBACK", target.parameters["redirect_uri"])
        assertTrue("https://outlook.office.com/IMAP.AccessAsUser.All" in target.parameters["scope"]!!.split(" "))
        assertNotNull(target.parameters["state"])
        assertNotNull(target.parameters["nonce"])
        assertEquals("S256", target.parameters["code_challenge_method"])
        // The secret stays on the server; only the token exchange sends it.
        assertNull(target.parameters["client_secret"])
    }

    @Test
    fun `a provider without a client cannot be started`() = testApplication {
        setUpUser()
        installRoutes()
        val client = createClient { followRedirects = false }

        assertEquals(HttpStatusCode.NotFound, client.get("$PROVIDERS/google").status)
        assertEquals(HttpStatusCode.NotFound, client.get("$PROVIDERS/nonsense").status)
    }

    @Test
    fun `without a session nothing is started`() = testApplication {
        setUpUser()
        signedIn = null
        installRoutes()
        val client = createClient { followRedirects = false }

        assertEquals(HttpStatusCode.Unauthorized, client.get("$PROVIDERS/microsoft").status)
    }

    @Test
    fun `the callback takes a state once`() = testApplication {
        setUpUser()
        installRoutes()
        val client = createClient { followRedirects = false }

        val state = client.startSignIn()

        // From the provider's page, so no session -- the state is what says who this is.
        signedIn = null
        assertEquals(HttpStatusCode.Found, client.callback(state).status)
        assertEquals(HttpStatusCode.BadRequest, client.callback(state).status)
    }

    @Test
    fun `the callback refuses a state it never issued`() = testApplication {
        setUpUser()
        installRoutes()

        assertEquals(HttpStatusCode.BadRequest, client.callback("made-up").status)
        assertEquals(HttpStatusCode.BadRequest, client.get(CALLBACK).status)
        assertTrue(tokenRequests.isEmpty())
    }

    @Test
    fun `the callback trades the code for tokens with the client's credentials`() = testApplication {
        setUpUser()
        installRoutes()
        val client = createClient { followRedirects = false }

        val response = client.callback(client.startSignIn())
        assertEquals(HttpStatusCode.Found, response.status)

        val request = assertNotNull(tokenRequests.singleOrNull())
        assertEquals("authorization_code", request["grant_type"])
        assertEquals(GOOD_CODE, request["code"])
        assertEquals("https://overmail.example$CALLBACK", request["redirect_uri"])
        assertEquals(CLIENT_ID, request["client_id"])
        assertEquals(CLIENT_SECRET, request["client_secret"])
        assertNotNull(request["code_verifier"])

        // The bearer stays on the server.
        assertFalse(response.headers[HttpHeaders.Location]!!.contains(ACCESS_TOKEN))
    }

    @Test
    fun `the callback sends the browser back to the settings to continue with the mailbox`() = testApplication {
        val user = setUpUser()
        installRoutes()
        val client = createClient { followRedirects = false }

        val state = client.startSignIn()
        signedIn = null
        val target = Url(client.callback(state).headers[HttpHeaders.Location]!!)
        assertEquals("overmail.example", target.host)
        assertEquals("email-accounts", target.parameters["settings"])
        val onboardingId = assertNotNull(target.parameters["_authentikt_session_id"])

        signedIn = user
        val response = client.get("$PROVIDERS/onboardings/$onboardingId")
        assertEquals(HttpStatusCode.OK, response.status)
        val onboarding = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("microsoft", onboarding["provider"]!!.jsonPrimitive.content)
        assertEquals("outlook.office365.com", onboarding["host"]!!.jsonPrimitive.content)
        assertEquals(MAILBOX, onboarding["username"]!!.jsonPrimitive.content)
        assertFalse(response.bodyAsText().contains(ACCESS_TOKEN))
    }

    @Test
    fun `an onboarding is only there for the user who signed in`() = testApplication {
        setUpUser()
        installRoutes()
        val client = createClient { followRedirects = false }

        val target = Url(client.callback(client.startSignIn()).headers[HttpHeaders.Location]!!)
        val onboardingId = target.parameters["_authentikt_session_id"]!!

        setUpUser()
        assertEquals(HttpStatusCode.NotFound, client.get("$PROVIDERS/onboardings/$onboardingId").status)
        assertEquals(HttpStatusCode.NotFound, client.get("$PROVIDERS/onboardings/made-up").status)
    }

    @Test
    fun `a sign-in still at the provider has no mailbox yet`() = testApplication {
        setUpUser()
        installRoutes()
        val client = createClient { followRedirects = false }

        client.startSignIn()
        // Every flow that has not come back from its provider, this one among them.
        val pending = sessions.filterValues { it.identifiedUser == null }.keys
        assertTrue(pending.isNotEmpty())
        pending.forEach { id ->
            assertEquals(HttpStatusCode.NotFound, client.get("$PROVIDERS/onboardings/$id").status)
        }
    }

    @Test
    fun `a code the provider refuses brings no mailbox and uses up the state`() = testApplication {
        setUpUser()
        installRoutes()
        val client = createClient { followRedirects = false }

        val state = client.startSignIn()
        assertNotEquals(HttpStatusCode.Found, client.callback(state, code = "stale").status)
        assertEquals(HttpStatusCode.BadRequest, client.callback(state).status)
        assertEquals(1, tokenRequests.size)
    }

    @Test
    fun `an id token the provider did not sign is not believed`() = testApplication {
        setUpUser()
        installRoutes()
        val client = createClient { followRedirects = false }

        signingSecret = "somebody-else"
        assertNotEquals(HttpStatusCode.Found, client.callback(client.startSignIn()).status)
    }

    @Test
    fun `a sign-in cancelled at the provider asks for no tokens`() = testApplication {
        setUpUser()
        installRoutes()
        val client = createClient { followRedirects = false }

        val response = client.get("$CALLBACK?state=${client.startSignIn()}&error=access_denied")
        assertNotEquals(HttpStatusCode.Found, response.status)
        assertTrue(tokenRequests.isEmpty())
    }

    @Test
    fun `a sign-in that came back is kept with its tokens`() = testApplication {
        val user = setUpUser()
        installRoutes()
        val client = createClient { followRedirects = false }

        val onboardingId = client.signInToMailbox()

        val grant = database.query {
            OAuthGrant.find { OAuthGrants.onboardingId eq onboardingId }.single().let {
                listOf(it.user.id.value, it.provider, it.address, it.accessToken, it.refreshToken, it.imapAccount)
            }
        }
        assertEquals(listOf(user.id.value, "microsoft", MAILBOX, ACCESS_TOKEN, "refresh", null), grant)
    }

    @Test
    fun `a sign-in that came back outlives its flow`() = testApplication {
        val user = setUpUser()
        installRoutes()
        val client = createClient { followRedirects = false }

        val onboardingId = client.signInToMailbox()
        // What a restart leaves of authentikt's flows, which live in memory only.
        sessions.clear()

        signedIn = user
        assertEquals(HttpStatusCode.OK, client.get("$PROVIDERS/onboardings/$onboardingId").status)
    }

    @Test
    fun `submitting a sign-in creates an inbox that logs in with its grant`() = testApplication {
        val user = setUpUser()
        installRoutes()
        val client = createClient { followRedirects = false }

        val onboardingId = client.signInToMailbox()
        signedIn = user

        val response = client.submit(onboardingId)
        assertEquals(HttpStatusCode.Created, response.status)
        val inboxId = Uuid.parse(Json.parseToJsonElement(response.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content)

        val (account, grant, importer) = database.query {
            val account = ImapAccount[inboxId].let { listOf(it.user.id.value, it.host, it.port, it.username, it.password) }
            val grant = OAuthGrant.find { OAuthGrants.imapAccount eq inboxId }.single()
            Triple(account, grant.onboardingId to grant.accessToken, ImapAccount[inboxId].toConnection(oauthGrantStates(inboxId)[inboxId]).authentication)
        }
        assertEquals(listOf(user.id.value, "outlook.office365.com", 993, MAILBOX, ""), account)
        assertEquals(null to ACCESS_TOKEN, grant)
        // What the importer logs in with.
        assertEquals(ImapClient.Auth.BearerAuth(MAILBOX, ACCESS_TOKEN), importer)

        // Used up: neither the dialog nor a second submit finds it again.
        assertEquals(HttpStatusCode.NotFound, client.get("$PROVIDERS/onboardings/$onboardingId").status)
        assertEquals(HttpStatusCode.NotFound, client.submit(onboardingId).status)
    }

    @Test
    fun `signing in again to an inbox whose sign-in was refused renews it instead of adding a second`() = testApplication {
        val user = setUpUser()
        installRoutes()
        val client = createClient { followRedirects = false }

        val inboxId = client.submit(client.signInToMailbox()).bodyAsText()
            .let { Uuid.parse(Json.parseToJsonElement(it).jsonObject["id"]!!.jsonPrimitive.content) }
        database.query {
            OAuthGrant.find { OAuthGrants.imapAccount eq inboxId }.single().apply {
                requiresReauthentication = true
                accessToken = "the-revoked-one"
            }
        }

        val again = client.signInToMailbox()
        signedIn = user

        val onboarding = Json.parseToJsonElement(client.get("$PROVIDERS/onboardings/$again").bodyAsText()).jsonObject
        assertEquals(inboxId.toString(), onboarding["reauthenticated_inbox_id"]!!.jsonPrimitive.content)
        // Nothing to submit: the inbox has its tokens back, and there is still only the one.
        assertEquals(HttpStatusCode.NotFound, client.submit(again).status)
        val grant = database.query {
            OAuthGrant.find { OAuthGrants.imapAccount eq inboxId }.single().let { it.accessToken to it.requiresReauthentication }
        }
        assertEquals(ACCESS_TOKEN to false, grant)
        assertEquals(1L, database.query { ImapAccount.find { ImapAccounts.user eq user.id }.count() })
    }

    @Test
    fun `signing in again to an inbox that is fine is the conflict it always was`() = testApplication {
        setUpUser()
        installRoutes()
        val client = createClient { followRedirects = false }

        assertEquals(HttpStatusCode.Created, client.submit(client.signInToMailbox()).status)

        val again = client.signInToMailbox()
        val onboarding = Json.parseToJsonElement(client.get("$PROVIDERS/onboardings/$again").bodyAsText()).jsonObject
        assertEquals(JsonNull, onboarding["reauthenticated_inbox_id"])
        assertEquals(HttpStatusCode.Conflict, client.submit(again).status)
    }

    @Test
    fun `a sign-in is only submitted by the user who signed in`() = testApplication {
        val owner = setUpUser()
        installRoutes()
        val client = createClient { followRedirects = false }

        val onboardingId = client.signInToMailbox()

        setUpUser()
        assertEquals(HttpStatusCode.NotFound, client.submit(onboardingId).status)
        assertEquals(0L, database.query { ImapAccount.find { ImapAccounts.user eq owner.id }.count() })
    }

    @Test
    fun `a sign-in submitted without folders is refused`() = testApplication {
        val user = setUpUser()
        installRoutes()
        val client = createClient { followRedirects = false }

        val onboardingId = client.signInToMailbox()
        signedIn = user

        assertEquals(HttpStatusCode.BadRequest, client.submit(onboardingId, folders = "").status)
        assertEquals(HttpStatusCode.OK, client.get("$PROVIDERS/onboardings/$onboardingId").status)
    }

    /** An id token as the provider signs it, for the mailbox and the last sign-in's nonce. */
    private fun idToken(): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val now = System.currentTimeMillis() / 1000
        val header = encoder.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val payload = encoder.encodeToString(
            """{"aud":"$CLIENT_ID","sub":"someone","email":"$MAILBOX","nonce":"$nonce","iat":$now,"exp":${now + 600}}""".toByteArray()
        )
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(signingSecret.toByteArray(), "HmacSHA256")) }
        val signature = encoder.encodeToString(mac.doFinal("$header.$payload".toByteArray()))
        return "$header.$payload.$signature"
    }

    private suspend fun HttpClient.startSignIn(): String {
        val target = Url(get("$PROVIDERS/microsoft").headers[HttpHeaders.Location]!!)
        nonce = target.parameters["nonce"]
        return target.parameters["state"]!!
    }

    /** Signs in at the provider and answers the onboarding the browser is sent back with. */
    private suspend fun HttpClient.signInToMailbox(): String {
        val user = signedIn
        val state = startSignIn()
        signedIn = null
        val target = Url(callback(state).headers[HttpHeaders.Location]!!)
        signedIn = user
        return target.parameters["_authentikt_session_id"]!!
    }

    private suspend fun HttpClient.submit(
        onboardingId: String,
        folders: String = """{"folder_name":"INBOX","imap_push":true,"ai_import":{"type":"only_new_messages"}}""",
    ): HttpResponse = post("$PROVIDERS/onboardings/$onboardingId/submit") {
        contentType(ContentType.Application.Json)
        setBody("""{"folder_settings":[$folders]}""")
    }

    private suspend fun HttpClient.callback(state: String, code: String = GOOD_CODE): HttpResponse =
        get("$CALLBACK?state=$state&code=$code")

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

    private fun ApplicationTestBuilder.installRoutes() {
        application {
            install(ContentNegotiation) { json() }
            installApiErrorHandling()
            install(Authentication) { session() }
            install(Koin) {
                modules(module {
                    single<OvermailDatabase> { database }
                    single<OAuthProviders> { providers }
                    single<OAuthTokens> { tokens }
                    // A manager on a scope that is already over: the submit reboots the importer after
                    // answering, and a test has no mailbox for it to connect to.
                    single<LegacyImporterManager> {
                        LegacyImporterManager(
                            database = database,
                            emailInserter = EmailInserterImpl(database),
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
                })
            }
            installOAuthOnboardings()
            routing {
                route(PROVIDERS) {
                    getOAuthProviders()
                    route("/{provider}") { startOAuth() }
                    route("/onboardings/{onboardingId}") {
                        getOAuthOnboarding()
                        route("/submit") { oauthInboxSubmitRoute() }
                    }
                }
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
