package es.jvbabi.overmail.server.http.users.me.password

import es.jvbabi.overmail.server.auth.hashPassword
import es.jvbabi.overmail.server.auth.verifyPassword
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.database.models.Users
import es.jvbabi.overmail.server.http.api.installApiErrorHandling
import io.ktor.client.request.delete
import io.ktor.client.request.get
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select

private const val ROUTE = "/api/users/me/password"

/** Setting, changing and removing the password the sign-in asks for instead of a mailed code. */
class PasswordRoutesTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:password-routes;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private var signedIn: User? = null

    @Test
    fun `the first password needs no current one`() = testApplication {
        val user = setUpUser(password = null)
        installRoutes()

        assertEquals(false, isSet())

        val response = put("""{"new_password": "correct horse"}""")

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals(true, isSet())
        assertTrue(verifyPassword("correct horse", storedHash(user)!!))
    }

    @Test
    fun `changing it takes the current one, and a wrong one changes nothing`() = testApplication {
        val user = setUpUser(password = "old password")
        installRoutes()

        val missing = put("""{"new_password": "new password"}""")
        assertEquals(HttpStatusCode.BadRequest, missing.status)
        assertEquals("current_password", parameterOf(missing))

        val wrong = put("""{"current_password": "guessed", "new_password": "new password"}""")
        assertEquals(HttpStatusCode.BadRequest, wrong.status)
        assertEquals("current_password", parameterOf(wrong))
        assertTrue(verifyPassword("old password", storedHash(user)!!))

        val right = put("""{"current_password": "old password", "new_password": "new password"}""")
        assertEquals(HttpStatusCode.NoContent, right.status)
        assertTrue(verifyPassword("new password", storedHash(user)!!))
    }

    @Test
    fun `a password that is too short or longer than bcrypt reads is refused`() = testApplication {
        val user = setUpUser(password = null)
        installRoutes()

        val short = put("""{"new_password": "short"}""")
        assertEquals(HttpStatusCode.BadRequest, short.status)
        assertEquals("new_password", parameterOf(short))

        val long = put("""{"new_password": "${"ä".repeat(37)}"}""")
        assertEquals(HttpStatusCode.BadRequest, long.status)

        assertNull(storedHash(user))
    }

    @Test
    fun `removing it takes the current one`() = testApplication {
        val user = setUpUser(password = "old password")
        installRoutes()

        val wrong = client.delete(ROUTE) { json("""{"current_password": "guessed"}""") }
        assertEquals(HttpStatusCode.BadRequest, wrong.status)
        assertNotNull(storedHash(user))

        val right = client.delete(ROUTE) { json("""{"current_password": "old password"}""") }
        assertEquals(HttpStatusCode.NoContent, right.status)
        assertNull(storedHash(user))
        assertEquals(false, isSet())
    }

    @Test
    fun `without a session nothing is read or written`() = testApplication {
        val user = setUpUser(password = null)
        signedIn = null
        installRoutes()

        assertEquals(HttpStatusCode.Unauthorized, client.get(ROUTE).status)
        assertEquals(HttpStatusCode.Unauthorized, put("""{"new_password": "correct horse"}""").status)
        assertNull(storedHash(user))
    }

    private suspend fun ApplicationTestBuilder.isSet(): Boolean {
        val body = Json.parseToJsonElement(client.get(ROUTE).bodyAsText()).jsonObject
        return body["is_set"]!!.jsonPrimitive.content.toBoolean()
    }

    private suspend fun ApplicationTestBuilder.put(body: String): HttpResponse =
        client.put(ROUTE) { json(body) }

    private fun io.ktor.client.request.HttpRequestBuilder.json(body: String) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun parameterOf(response: HttpResponse): String =
        Json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!.jsonObject["details"]!!
            .jsonObject["parameter"]!!.jsonPrimitive.content

    private suspend fun storedHash(user: User): String? = database.query {
        Users.select(Users.password).where { Users.id eq user.id }.single()[Users.password]
    }

    private suspend fun setUpUser(password: String?): User {
        database.init()
        val hash = password?.let { hashPassword(it) }
        return database.query {
            User.new {
                username = "owner-${Uuid.random()}"
                email = "owner-${Uuid.random()}@example.com"
                firstname = "Julius"
                lastname = "Babies"
                this.password = hash
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
                    getPassword()
                    setPassword()
                    deletePassword()
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
