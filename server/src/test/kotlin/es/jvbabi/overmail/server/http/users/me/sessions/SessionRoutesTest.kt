package es.jvbabi.overmail.server.http.users.me.sessions

import es.jvbabi.overmail.server.auth.JwtService
import es.jvbabi.overmail.server.auth.issueSession
import es.jvbabi.overmail.server.auth.overmailSession
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Session
import es.jvbabi.overmail.server.database.models.Sessions
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.http.api.installApiErrorHandling
import es.jvbabi.overmail.server.http.users.me.sessions.item.revokeSession
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/** Listing and revoking sessions, through the real session provider. */
class SessionRoutesTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:sessions;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private val jwtService = JwtService(createTempDirectory("jwt").toString())

    private val phone = Session.Client.Android(device = "Pixel 8", manufacturer = "Google", os = "Android 15")
    private val browser = Session.Client.Web(browser = "Firefox 131", device = "Mac", os = "macOS")

    @Test
    fun `lists the unrevoked sessions of the caller and marks the current one`() = testApplication {
        val user = newUser()
        val current = jwtService.issueSession(database, user, browser)
        val other = jwtService.issueSession(database, user, phone)
        val revoked = jwtService.issueSession(database, user, phone)
        jwtService.issueSession(database, newUser(), browser)
        installRoutes()
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/users/me/sessions/${idOf(revoked)}") { bearerAuth(current) }.status)

        val sessions = listSessions(current)

        assertEquals(setOf(idOf(current), idOf(other)), sessions.map { it["id"]!!.jsonPrimitive.content }.toSet())
        val currentEntry = sessions.single { it["is_current_session"]!!.jsonPrimitive.boolean }
        assertEquals(idOf(current), currentEntry["id"]!!.jsonPrimitive.content)
        assertEquals("web", currentEntry["client"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("Firefox 131", currentEntry["client"]!!.jsonObject["browser"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a revoked session no longer signs anybody in`() = testApplication {
        val user = newUser()
        val current = jwtService.issueSession(database, user, browser)
        val other = jwtService.issueSession(database, user, phone)
        installRoutes()

        assertEquals(HttpStatusCode.OK, client.get("/api/users/me/sessions") { bearerAuth(other) }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/users/me/sessions/${idOf(other)}") { bearerAuth(current) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/users/me/sessions") { bearerAuth(other) }.status)

        // Revoked is gone: asking again is a miss, not a second revocation.
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/users/me/sessions/${idOf(other)}") { bearerAuth(current) }.status)
    }

    @Test
    fun `revoking the current session signs the caller out`() = testApplication {
        val current = jwtService.issueSession(database, newUser(), browser)
        installRoutes()

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/users/me/sessions/${idOf(current)}") { bearerAuth(current) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/users/me/sessions") { bearerAuth(current) }.status)
    }

    @Test
    fun `somebody else's session cannot be revoked`() = testApplication {
        val current = jwtService.issueSession(database, newUser(), browser)
        val foreign = jwtService.issueSession(database, newUser(), phone)
        installRoutes()

        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/users/me/sessions/${idOf(foreign)}") { bearerAuth(current) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/users/me/sessions") { bearerAuth(foreign) }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/users/me/sessions/${Uuid.random()}") { bearerAuth(current) }.status)
    }

    @Test
    fun `a genuine token nobody recorded is adopted as an unknown browser`() = testApplication {
        val token = jwtService.issue(newUser())
        installRoutes()

        val session = listSessions(token).single()
        assertEquals(true, session["is_current_session"]!!.jsonPrimitive.boolean)
        assertEquals("unknown", session["client"]!!.jsonObject["browser"]!!.jsonPrimitive.content)
    }

    private suspend fun ApplicationTestBuilder.listSessions(token: String): List<JsonObject> {
        val response = client.get("/api/users/me/sessions") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, response.status)
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject["sessions"]!!.jsonArray.map { it.jsonObject }
    }

    private suspend fun idOf(token: String): String = database.query {
        Sessions.select(Sessions.id).where { Sessions.token eq token }.single()[Sessions.id].value.toString()
    }

    private suspend fun newUser(): Uuid {
        database.init()
        return database.query {
            User.new {
                username = "user-${Uuid.random()}"
                email = "user-${Uuid.random()}@example.com"
                firstname = "Julius"
                lastname = "Babies"
            }.id.value
        }
    }

    private fun ApplicationTestBuilder.installRoutes() {
        application {
            install(ContentNegotiation) { json() }
            installApiErrorHandling()
            install(Authentication) { overmailSession() }
            dependencies {
                provide<OvermailDatabase> { database }
                provide<JwtService> { jwtService }
            }
            routing {
                route("/api/users/me/sessions") {
                    getSessions()
                    route("/{sessionId}") { revokeSession() }
                }
            }
        }
    }
}
