package es.jvbabi.overmail.server.http.users.me.sessions

import es.jvbabi.overmail.server.auth.JwtService
import es.jvbabi.overmail.server.auth.issueSession
import es.jvbabi.overmail.server.auth.overmailSession
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Session
import es.jvbabi.overmail.server.database.models.Sessions
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.http.api.installApiErrorHandling
import es.jvbabi.overmail.server.http.users.me.sessions.current.setFirebaseInstallationId
import es.jvbabi.overmail.server.http.users.me.sessions.item.revokeSession
import es.jvbabi.overmail.server.http.users.me.sessions.item.sendTestPush
import es.jvbabi.overmail.server.jobs.push.PushNotifications
import es.jvbabi.overmail.server.jobs.push.PushQueue
import es.jvbabi.overmail.server.jobs.push.PushSender
import io.ktor.client.request.post
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
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
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin

/** Listing and revoking sessions, through the real session provider. */
class SessionRoutesTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:sessions;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private val jwtService = JwtService(createTempDirectory("jwt").toString())

    private val phone = Session.Client.Android(device = "Pixel 8", manufacturer = "Google", os = "Android 15")
    private val browser = Session.Client.Web(browser = "Firefox 131", device = "Mac", os = "macOS")

    /** The installation ids pushes were handed to, in order. */
    private val pushed = Channel<String>(Channel.UNLIMITED)
    private var pushQueue = PushQueue(database, PushSender { token, _, _ -> pushed.send(token); PushSender.Result.Sent })

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

    @Test
    fun `a firebase installation id is stored on the session it came with, and the last one wins`() = testApplication {
        val user = newUser()
        val current = jwtService.issueSession(database, user, phone)
        val other = jwtService.issueSession(database, user, phone)
        installRoutes()

        assertEquals(HttpStatusCode.NoContent, setFirebaseInstallationId(current, "first").status)
        assertEquals(HttpStatusCode.NoContent, setFirebaseInstallationId(current, "second").status)

        assertEquals(phone.copy(firebaseInstallationId = "second"), clientOf(current))
        assertEquals(phone, clientOf(other))
    }

    @Test
    fun `a firebase installation id does not show up in the listing`() = testApplication {
        val current = jwtService.issueSession(database, newUser(), phone)
        installRoutes()
        setFirebaseInstallationId(current, "secret")

        val client = listSessions(current).single()["client"]!!.jsonObject

        assertEquals("Pixel 8", client["device"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, client["firebase_installation_id"])
    }

    @Test
    fun `only a session of the android app takes a firebase installation id`() = testApplication {
        val current = jwtService.issueSession(database, newUser(), browser)
        installRoutes()

        assertEquals(HttpStatusCode.Conflict, setFirebaseInstallationId(current, "token").status)
        assertEquals(browser, clientOf(current))
    }

    @Test
    fun `a blank firebase installation id is refused`() = testApplication {
        val current = jwtService.issueSession(database, newUser(), phone)
        installRoutes()

        assertEquals(HttpStatusCode.BadRequest, setFirebaseInstallationId(current, " ").status)
        assertEquals(phone, clientOf(current))
    }

    @Test
    fun `the listing says how far each session is with push`() = testApplication {
        val user = newUser()
        val current = jwtService.issueSession(database, user, browser)
        val phoneWithPush = jwtService.issueSession(database, user, phone)
        val phoneWithoutPush = jwtService.issueSession(database, user, phone)
        installRoutes()
        setFirebaseInstallationId(phoneWithPush, "installation")

        val states = listSessions(current).associate { it["id"]!!.jsonPrimitive.content to it["push_notification_state"]!!.jsonPrimitive.content }

        assertEquals(
            mapOf(idOf(current) to "unavailable", idOf(phoneWithPush) to "setup", idOf(phoneWithoutPush) to "available"),
            states,
        )
    }

    @Test
    fun `a test push goes to the device of the session`() = testApplication {
        val user = newUser()
        val current = jwtService.issueSession(database, user, browser)
        val phoneWithPush = jwtService.issueSession(database, user, phone)
        jwtService.issueSession(database, user, phone.copy(firebaseInstallationId = "other"))
        installRoutes()
        setFirebaseInstallationId(phoneWithPush, "installation")

        assertEquals(HttpStatusCode.Accepted, client.post("/api/users/me/sessions/${idOf(phoneWithPush)}/test-push") { bearerAuth(current) }.status)

        val consumer = CoroutineScope(Dispatchers.Default).launch { pushQueue.consume() }
        try {
            assertEquals("installation", withTimeout(5.seconds) { pushed.receive() })
            assertEquals(null, pushed.tryReceive().getOrNull())
        } finally {
            consumer.cancel()
        }
    }

    @Test
    fun `a test push needs a session that set up push and belongs to the caller`() = testApplication {
        val user = newUser()
        val current = jwtService.issueSession(database, user, browser)
        val phoneWithoutPush = jwtService.issueSession(database, user, phone)
        val foreign = jwtService.issueSession(database, newUser(), phone)
        installRoutes()
        setFirebaseInstallationId(foreign, "installation")

        assertEquals(HttpStatusCode.Conflict, client.post("/api/users/me/sessions/${idOf(current)}/test-push") { bearerAuth(current) }.status)
        assertEquals(HttpStatusCode.Conflict, client.post("/api/users/me/sessions/${idOf(phoneWithoutPush)}/test-push") { bearerAuth(current) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/users/me/sessions/${idOf(foreign)}/test-push") { bearerAuth(current) }.status)
    }

    @Test
    fun `a test push says so when the server cannot push at all`() = testApplication {
        pushQueue = PushQueue(database, PushSender.Disabled)
        val user = newUser()
        val current = jwtService.issueSession(database, user, browser)
        val phoneWithPush = jwtService.issueSession(database, user, phone)
        installRoutes()
        setFirebaseInstallationId(phoneWithPush, "installation")

        assertEquals(HttpStatusCode.ServiceUnavailable, client.post("/api/users/me/sessions/${idOf(phoneWithPush)}/test-push") { bearerAuth(current) }.status)
    }

    private suspend fun ApplicationTestBuilder.setFirebaseInstallationId(token: String, firebaseInstallationId: String) =
        client.put("/api/users/me/sessions/current/firebase-installation-id") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"installation_id":"$firebaseInstallationId"}""")
        }

    private suspend fun clientOf(token: String): Session.Client = database.query {
        Sessions.select(Sessions.client).where { Sessions.token eq token }.single()[Sessions.client]
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
            install(Koin) {
                modules(module {
                    single<OvermailDatabase> { database }
                    single<JwtService> { jwtService }
                    single<PushNotifications> { PushNotifications(pushQueue) }
                })
            }
            routing {
                route("/api/users/me/sessions") {
                    getSessions()
                    route("/current/firebase-installation-id") { setFirebaseInstallationId() }
                    route("/{sessionId}") {
                        revokeSession()
                        route("/test-push") { sendTestPush() }
                    }
                }
            }
        }
    }
}
