package es.jvbabi.overmail.server.http.users.me

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.User
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class GetCurrentUserTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:current-user;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private var signedIn: User? = null

    @Test
    fun `reports the signed-in user and lets the browser keep the answer`() = testApplication {
        val user = setUpUser()
        installRoute()

        val response = client.get("/api/users/me")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(user.id.value.toString(), body["id"]!!.jsonPrimitive.content)
        assertEquals("Julius", body["firstname"]!!.jsonPrimitive.content)
        assertEquals("Babies", body["lastname"]!!.jsonPrimitive.content)

        val cacheControl = response.headers[HttpHeaders.CacheControl]!!
        // Private: the answer is one user's name, so no proxy may hand it to the next request.
        assertTrue(cacheControl.contains("private"), cacheControl)
        assertTrue(cacheControl.contains("max-age=300"), cacheControl)
    }

    @Test
    fun `without a session there is nobody to report`() = testApplication {
        setUpUser()
        signedIn = null
        installRoute()

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/users/me").status)
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

    private fun ApplicationTestBuilder.installRoute() {
        application {
            install(ContentNegotiation) { json() }
            install(Authentication) { session() }
            routing {
                route("/api/users/me") { getCurrentUser() }
            }
        }
    }

    /**
     * Signed in as whoever [signedIn] holds, and a bare 401 while it holds nobody -- the same
     * two answers the real provider in `auth/SessionAuthentication.kt` gives.
     */
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
