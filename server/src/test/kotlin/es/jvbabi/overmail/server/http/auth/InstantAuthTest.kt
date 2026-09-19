package es.jvbabi.overmail.server.http.auth

import es.jvbabi.overmail.server.auth.JwtService
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.http.api.installApiErrorHandling
import es.jvbabi.overmail.server.http.webapp.devices.AuthCodeSession
import es.jvbabi.overmail.server.http.webapp.devices.authCodeSessions
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * The code an app scans, traded for a session token. Holding the code is the whole authentication
 * here, so what matters is that it works exactly once and not a minute past its life.
 */
class InstantAuthTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:instant-auth;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private val jwtService = JwtService(createTempDirectory("instant-auth").toString())

    @Test
    fun `a code becomes a token for the user it was made for`() = testApplication {
        val user = setUp()
        installRoutes()

        val code = storeCode(user, validFor = 5.minutes)
        val response = client.get("/api/auth/instant-auth?code=$code")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.json()
        assertEquals(user.id.value.toString(), body["user_id"]!!.jsonPrimitive.content)
        assertEquals(user.username, body["username"]!!.jsonPrimitive.content)
        assertEquals(user.id.value, jwtService.userIdOf(body["token"]!!.jsonPrimitive.content))
    }

    @Test
    fun `a code is spent by the first caller`() = testApplication {
        val user = setUp()
        installRoutes()

        val code = storeCode(user, validFor = 5.minutes)
        assertEquals(HttpStatusCode.OK, client.get("/api/auth/instant-auth?code=$code").status)

        val second = client.get("/api/auth/instant-auth?code=$code")
        assertEquals(HttpStatusCode.NotFound, second.status)
        assertEquals("not_found", second.json()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertNull(authCodeSessions[code])
    }

    @Test
    fun `a code that ran out is gone, not merely unknown`() = testApplication {
        val user = setUp()
        installRoutes()

        val code = storeCode(user, validFor = (-1).minutes)
        val response = client.get("/api/auth/instant-auth?code=$code")
        assertEquals(HttpStatusCode.Gone, response.status)
        assertEquals("gone", response.json()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `without a code the request is the thing that is wrong`() = testApplication {
        setUp()
        installRoutes()

        val response = client.get("/api/auth/instant-auth")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("invalid_request", response.json()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    private fun storeCode(user: User, validFor: kotlin.time.Duration): String {
        val code = "code-${Uuid.random()}"
        authCodeSessions[code] = AuthCodeSession(user, Clock.System.now() + validFor, code)
        return code
    }

    private suspend fun HttpResponse.json() = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun setUp(): User {
        database.init()
        return database.query {
            User.new {
                username = "scanner-${Uuid.random()}"
                email = "scanner-${Uuid.random()}@example.com"
                firstname = "Test"
                lastname = "Scanner"
            }
        }
    }

    private fun ApplicationTestBuilder.installRoutes() {
        application {
            install(ContentNegotiation) { json() }
            installApiErrorHandling()
            dependencies {
                provide<JwtService> { jwtService }
                provide<OvermailDatabase> { database }
            }
            routing {
                route("/api/auth/instant-auth") { instantAuth() }
            }
        }
    }
}
