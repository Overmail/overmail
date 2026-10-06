package es.jvbabi.overmail.server.http.users.me.inboxes.create.oauth

import es.jvbabi.overmail.server.config.OAuthClientConfig
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.http.api.installApiErrorHandling
import es.jvbabi.overmail.server.http.oauth.oauthCallback
import es.jvbabi.overmail.server.oauth.OAuthOnboardingStore
import es.jvbabi.overmail.server.oauth.OAuthProviders
import es.jvbabi.overmail.server.oauth.OAuthStateStore
import es.jvbabi.overmail.server.oauth.OAuthTokenClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import java.util.Base64

private const val PROVIDERS = "/api/users/me/inboxes/create/oauth"
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

    /** Microsoft has a client, Google has none, and the typo is ignored rather than fatal. */
    private val providers = OAuthProviders(
        clients = mapOf(
            "microsoft" to OAuthClientConfig(clientId = "the-client", clientSecret = "the-secret"),
            "microsfot" to OAuthClientConfig(clientId = "typo", clientSecret = "typo"),
        ),
        baseUrl = "https://overmail.example",
    )

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
        assertEquals("login.microsoftonline.com", target.host)
        assertEquals("the-client", target.parameters["client_id"])
        assertEquals("code", target.parameters["response_type"])
        assertEquals("https://overmail.example/api/oauth/microsoft/callback", target.parameters["redirect_uri"])
        // The secret stays on the server; only the token exchange sends it.
        assertEquals(null, target.parameters["client_secret"])
        assertNotNull(target.parameters["state"])
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
        assertEquals(HttpStatusCode.Found, client.callback("microsoft", state).status)
        assertEquals(HttpStatusCode.BadRequest, client.callback("microsoft", state).status)
    }

    @Test
    fun `the callback refuses a state it never issued`() = testApplication {
        setUpUser()
        installRoutes()

        assertEquals(HttpStatusCode.BadRequest, client.callback("microsoft", "made-up").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/oauth/microsoft/callback").status)
    }

    @Test
    fun `the callback trades the code for tokens with the client's credentials`() = testApplication {
        setUpUser()
        installRoutes()
        val client = createClient { followRedirects = false }

        val response = client.callback("microsoft", client.startSignIn())
        assertEquals(HttpStatusCode.Found, response.status)

        val request = assertNotNull(tokenRequests.singleOrNull())
        assertEquals("authorization_code", request["grant_type"])
        assertEquals(GOOD_CODE, request["code"])
        assertEquals("https://overmail.example/api/oauth/microsoft/callback", request["redirect_uri"])
        assertEquals("the-client", request["client_id"])
        assertEquals("the-secret", request["client_secret"])

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
        val target = Url(client.callback("microsoft", state).headers[HttpHeaders.Location]!!)
        assertEquals("overmail.example", target.host)
        assertEquals("email-accounts", target.parameters["settings"])
        val onboardingId = assertNotNull(target.parameters["continue_onboarding_oauth_imap_account"])

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

        val target = Url(client.callback("microsoft", client.startSignIn()).headers[HttpHeaders.Location]!!)
        val onboardingId = target.parameters["continue_onboarding_oauth_imap_account"]!!

        setUpUser()
        assertEquals(HttpStatusCode.NotFound, client.get("$PROVIDERS/onboardings/$onboardingId").status)
        assertEquals(HttpStatusCode.NotFound, client.get("$PROVIDERS/onboardings/made-up").status)
    }

    @Test
    fun `a code the provider refuses is a bad request and uses up the state`() = testApplication {
        setUpUser()
        installRoutes()
        val client = createClient { followRedirects = false }

        val state = client.startSignIn()
        assertEquals(HttpStatusCode.BadRequest, client.callback("microsoft", state, code = "stale").status)
        assertEquals(HttpStatusCode.BadRequest, client.callback("microsoft", state).status)
        assertEquals(1, tokenRequests.size)
    }

    @Test
    fun `a sign-in cancelled at the provider asks for no tokens`() = testApplication {
        setUpUser()
        installRoutes()
        val client = createClient { followRedirects = false }

        val response = client.get("/api/oauth/microsoft/callback?state=${client.startSignIn()}&error=access_denied")
        assertEquals(HttpStatusCode.Found, response.status)
        assertTrue(tokenRequests.isEmpty())

        // Back to the settings, with nothing to continue.
        val target = Url(response.headers[HttpHeaders.Location]!!)
        assertEquals("email-accounts", target.parameters["settings"])
        assertNull(target.parameters["continue_onboarding_oauth_imap_account"])
    }

    /** An id token as the provider signs it; only the payload is read, so the signature is a stand-in. */
    private fun idToken(email: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"RS256"}""".toByteArray())
        val payload = encoder.encodeToString("""{"email":"$email","sub":"someone"}""".toByteArray())
        return "$header.$payload.signature"
    }

    private suspend fun io.ktor.client.HttpClient.startSignIn(): String =
        Url(get("$PROVIDERS/microsoft").headers[HttpHeaders.Location]!!).parameters["state"]!!

    private suspend fun io.ktor.client.HttpClient.callback(
        provider: String,
        state: String,
        code: String = GOOD_CODE,
    ): HttpResponse = get("/api/oauth/$provider/callback?state=$state&code=$code")

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
        // Microsoft's token endpoint, as far as the code exchange is concerned.
        externalServices {
            hosts("https://login.microsoftonline.com") {
                routing {
                    post("/common/oauth2/v2.0/token") {
                        val form = call.receiveParameters()
                        tokenRequests += form.entries().associate { it.key to it.value.single() }
                        if (form["code"] == GOOD_CODE) {
                            call.respondText(
                                """{"access_token":"$ACCESS_TOKEN","token_type":"Bearer","expires_in":3599,"refresh_token":"refresh","id_token":"${idToken(MAILBOX)}"}""",
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
            }
        }

        application {
            install(ContentNegotiation) { json() }
            installApiErrorHandling()
            install(Authentication) { session() }
            dependencies {
                provide<OvermailDatabase> { database }
                provide<OAuthProviders> { providers }
                provide<OAuthStateStore> { OAuthStateStore() }
                provide<OAuthOnboardingStore> { OAuthOnboardingStore() }
                // The test client, which sends the token request to the fake endpoint below.
                provide<OAuthTokenClient> { OAuthTokenClient(this@installRoutes.client) }
            }
            routing {
                route(PROVIDERS) {
                    getOAuthProviders()
                    route("/{provider}") { startOAuth() }
                    route("/onboardings/{onboardingId}") {
                        getOAuthOnboarding()
                    }
                }
                route("/api/oauth/{provider}/callback") { oauthCallback() }
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
