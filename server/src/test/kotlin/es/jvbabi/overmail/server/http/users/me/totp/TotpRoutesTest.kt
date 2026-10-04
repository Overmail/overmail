package es.jvbabi.overmail.server.http.users.me.totp

import dev.turingcomplete.kotlinonetimepassword.GoogleAuthenticator
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.database.models.Users
import es.jvbabi.overmail.server.http.api.installApiErrorHandling
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select

private const val ROUTE = "/api/users/me/totp"

/** Setting up the authenticator app the sign-in asks for a code from, and removing it again. */
class TotpRoutesTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:totp-routes;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private var signedIn: User? = null

    @Test
    fun `a scanned secret is stored only once a code from it comes back`() = testApplication {
        val user = setUpUser(totpSecret = null)
        installRoutes()

        val setup = Json.parseToJsonElement(client.post("$ROUTE/setup").bodyAsText()).jsonObject
        val secret = setup["secret"]!!.jsonPrimitive.content
        assertTrue(setup["uri"]!!.jsonPrimitive.content.startsWith("otpauth://totp/"))
        assertNull(storedSecret(user), "the setup alone stores nothing")

        val wrong = client.put(ROUTE) { json("""{"secret": "$secret", "code": "000000"}""") }
        assertEquals(HttpStatusCode.BadRequest, wrong.status)
        assertNull(storedSecret(user))

        val right = client.put(ROUTE) { json("""{"secret": "$secret", "code": "${codeFor(secret)}"}""") }
        assertEquals(HttpStatusCode.NoContent, right.status)
        assertEquals(secret, storedSecret(user))
        assertEquals(true, isEnabled())
    }

    @Test
    fun `one that is set up is not replaced`() = testApplication {
        val user = setUpUser(totpSecret = SECRET)
        installRoutes()

        val other = GoogleAuthenticator.createRandomSecret()
        val response = client.put(ROUTE) { json("""{"secret": "$other", "code": "${codeFor(other)}"}""") }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals(SECRET, storedSecret(user))
    }

    @Test
    fun `removing it takes a code from it`() = testApplication {
        val user = setUpUser(totpSecret = SECRET)
        installRoutes()

        val wrong = client.delete(ROUTE) { json("""{"code": "000000"}""") }
        assertEquals(HttpStatusCode.BadRequest, wrong.status)
        assertEquals(SECRET, storedSecret(user))

        val right = client.delete(ROUTE) { json("""{"code": "${codeFor(SECRET)}"}""") }
        assertEquals(HttpStatusCode.NoContent, right.status)
        assertNull(storedSecret(user))
        assertEquals(false, isEnabled())
    }

    @Test
    fun `the mailed code is active until it is turned off`() = testApplication {
        setUpUser(totpSecret = SECRET)
        installRoutes()

        assertEquals(true, emailOtpActive())

        val off = client.put("$ROUTE/email-otp") { json("""{"enabled": false}""") }
        assertEquals(HttpStatusCode.NoContent, off.status)
        assertEquals(false, emailOtpActive())
    }

    @Test
    fun `without a session nothing is read or written`() = testApplication {
        val user = setUpUser(totpSecret = null)
        signedIn = null
        installRoutes()

        assertEquals(HttpStatusCode.Unauthorized, client.get(ROUTE).status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("$ROUTE/setup").status)
        assertNull(storedSecret(user))
    }

    private suspend fun ApplicationTestBuilder.emailOtpActive(): Boolean {
        val body = Json.parseToJsonElement(client.get(ROUTE).bodyAsText()).jsonObject
        return body["email_otp_active"]!!.jsonPrimitive.content.toBoolean()
    }

    private fun codeFor(secret: String): String = GoogleAuthenticator(secret).generate()

    private suspend fun ApplicationTestBuilder.isEnabled(): Boolean {
        val body = Json.parseToJsonElement(client.get(ROUTE).bodyAsText()).jsonObject
        return body["is_enabled"]!!.jsonPrimitive.content.toBoolean()
    }

    private fun HttpRequestBuilder.json(body: String) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun storedSecret(user: User): String? = database.query {
        Users.select(Users.totpSecret).where { Users.id eq user.id }.single()[Users.totpSecret]
    }

    private suspend fun setUpUser(totpSecret: String?): User {
        database.init()
        return database.query {
            User.new {
                username = "owner-${Uuid.random()}"
                email = "owner-${Uuid.random()}@example.com"
                firstname = "Julius"
                lastname = "Babies"
                this.totpSecret = totpSecret
            }
        }.also { signedIn = it }
    }

    private fun ApplicationTestBuilder.installRoutes() {
        application {
            install(ContentNegotiation) { json() }
            installApiErrorHandling()
            install(Authentication) { session() }
            dependencies { provide<OvermailDatabase> { database } }
            routing {
                route(ROUTE) {
                    getTotp()
                    enableTotp()
                    disableTotp()
                    route("/setup") { setupTotp() }
                    route("/email-otp") { setEmailOtpActive() }
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

private val SECRET = GoogleAuthenticator.createRandomSecret()
