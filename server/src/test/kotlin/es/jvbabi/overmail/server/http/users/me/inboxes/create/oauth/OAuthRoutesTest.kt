package es.jvbabi.overmail.server.http.users.me.inboxes.create.oauth

import es.jvbabi.overmail.server.config.OAuthClientConfig
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.http.api.installApiErrorHandling
import es.jvbabi.overmail.server.http.oauth.oauthCallback
import es.jvbabi.overmail.server.oauth.OAuthProviders
import es.jvbabi.overmail.server.oauth.OAuthStateStore
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
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
import io.ktor.server.response.respond
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database

private const val PROVIDERS = "/api/users/me/inboxes/create/oauth"

class OAuthRoutesTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:oauth-routes;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private var signedIn: User? = null

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

        val state = Url(client.get("$PROVIDERS/microsoft").headers[HttpHeaders.Location]!!).parameters["state"]!!

        // From the provider's page, so no session -- the state is what says who this is.
        signedIn = null
        assertEquals(HttpStatusCode.OK, client.callback("microsoft", state).status)
        assertEquals(HttpStatusCode.BadRequest, client.callback("microsoft", state).status)
    }

    @Test
    fun `the callback refuses a state it never issued`() = testApplication {
        setUpUser()
        installRoutes()

        assertEquals(HttpStatusCode.BadRequest, client.callback("microsoft", "made-up").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/oauth/microsoft/callback").status)
    }

    private suspend fun io.ktor.client.HttpClient.callback(provider: String, state: String): HttpResponse =
        get("/api/oauth/$provider/callback?state=$state&code=whatever")

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
            dependencies {
                provide<OvermailDatabase> { database }
                provide<OAuthProviders> { providers }
                provide<OAuthStateStore> { OAuthStateStore() }
            }
            routing {
                route(PROVIDERS) {
                    getOAuthProviders()
                    route("/{provider}") { startOAuth() }
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
