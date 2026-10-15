package es.jvbabi.overmail.server.http.users.me.apikeys

import es.jvbabi.overmail.server.auth.JwtService
import es.jvbabi.overmail.server.auth.hashApiKey
import es.jvbabi.overmail.server.auth.issueSession
import es.jvbabi.overmail.server.auth.overmailSession
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.ApiKeys
import es.jvbabi.overmail.server.database.models.Session
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.http.api.installApiErrorHandling
import es.jvbabi.overmail.server.http.users.me.apikeys.item.deleteApiKey
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin

/** Making, listing and deleting api keys, and signing in with one, through the real provider. */
class ApiKeyRoutesTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:api-keys;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private val jwtService = JwtService(createTempDirectory("jwt").toString())

    private val browser = Session.Client.Web(browser = "Firefox 131", device = "Mac", os = "macOS")

    @Test
    fun `a new key is shown once and signs in as its user`() = testApplication {
        val session = jwtService.issueSession(database, newUser(), browser)
        installRoutes()

        val created = create(session, "Backup script")
        val key = created["key"]!!.jsonPrimitive.content
        val apiKey = created["api_key"]!!.jsonObject
        assertTrue(key.startsWith("om_"), key)
        assertTrue(key.startsWith(apiKey["prefix"]!!.jsonPrimitive.content))
        assertEquals("Backup script", apiKey["name"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, apiKey["last_used_at"])

        // The key works where a session does, and the list shows it used -- without the key.
        val listed = list(key).single()
        assertEquals(apiKey["id"], listed["id"])
        assertNotEquals(JsonNull, listed["last_used_at"])
        assertFalse(listed.containsKey("key"))
    }

    @Test
    fun `only the hash of a key is stored`() = testApplication {
        val session = jwtService.issueSession(database, newUser(), browser)
        installRoutes()

        val key = create(session, "Hashed")["key"]!!.jsonPrimitive.content

        // Every row, so a key stored in any column of any of them shows up.
        val rows = database.query { ApiKeys.selectAll().toList() }
        assertEquals(1, rows.count { it[ApiKeys.keyHash] == hashApiKey(key) })
        assertFalse(rows.any { row -> row.fieldIndex.keys.any { field -> row[field]?.toString() == key } })
    }

    @Test
    fun `a deleted key no longer signs anybody in`() = testApplication {
        val session = jwtService.issueSession(database, newUser(), browser)
        installRoutes()

        val created = create(session, "Short-lived")
        val key = created["key"]!!.jsonPrimitive.content
        val id = created["api_key"]!!.jsonObject["id"]!!.jsonPrimitive.content

        assertEquals(HttpStatusCode.OK, client.get("/api/users/me/api-keys") { bearerAuth(key) }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/users/me/api-keys/$id") { bearerAuth(session) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/users/me/api-keys") { bearerAuth(key) }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/users/me/api-keys/$id") { bearerAuth(session) }.status)
    }

    @Test
    fun `a made-up key signs nobody in`() = testApplication {
        installRoutes()

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/users/me/api-keys") { bearerAuth("om_nothing") }.status)
    }

    @Test
    fun `somebody else's keys are neither listed nor deletable`() = testApplication {
        val mine = jwtService.issueSession(database, newUser(), browser)
        val theirs = jwtService.issueSession(database, newUser(), browser)
        installRoutes()

        val foreign = create(theirs, "Theirs")["api_key"]!!.jsonObject["id"]!!.jsonPrimitive.content

        assertTrue(list(mine).isEmpty())
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/users/me/api-keys/$foreign") { bearerAuth(mine) }.status)
        assertEquals(1, list(theirs).size)
    }

    @Test
    fun `a key needs a name`() = testApplication {
        val session = jwtService.issueSession(database, newUser(), browser)
        installRoutes()

        assertEquals(HttpStatusCode.BadRequest, post(session, "   ").status)
        assertEquals(HttpStatusCode.BadRequest, post(session, "x".repeat(101)).status)
    }

    private suspend fun ApplicationTestBuilder.post(token: String, name: String) =
        client.post("/api/users/me/api-keys") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(mapOf("name" to name)))
        }

    private suspend fun ApplicationTestBuilder.create(token: String, name: String): JsonObject {
        val response = post(token, name)
        assertEquals(HttpStatusCode.Created, response.status)
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private suspend fun ApplicationTestBuilder.list(token: String): List<JsonObject> {
        val response = client.get("/api/users/me/api-keys") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, response.status)
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject["api_keys"]!!.jsonArray.map { it.jsonObject }
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
                })
            }
            routing {
                route("/api/users/me/api-keys") {
                    getApiKeys()
                    createApiKey()
                    route("/{apiKeyId}") { deleteApiKey() }
                }
            }
        }
    }
}
