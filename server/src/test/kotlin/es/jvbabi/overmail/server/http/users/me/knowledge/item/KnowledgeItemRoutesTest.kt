package es.jvbabi.overmail.server.http.users.me.knowledge.item

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Knowledge
import es.jvbabi.overmail.server.database.models.Knowledges
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.http.api.installApiErrorHandling
import io.ktor.client.request.delete
import io.ktor.client.request.put
import io.ktor.client.request.setBody
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
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll

private const val ROUTE = "/api/users/me/knowledge"

/** Correcting and forgetting one entry, and the ownership both of them turn on. */
class KnowledgeItemRoutesTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:knowledge-item;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private var signedIn: User? = null

    @Test
    fun `an edit replaces the entry and keeps its id`() = testApplication {
        val user = setUpUser()
        installRoute()
        val entry = knowledgeOf(user, name = "Umzug", byAgent = true)

        val before = database.query { Knowledges.selectAll().single()[Knowledges.updatedAt] }

        val response = client.put("$ROUTE/$entry") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "name": "Umzug nach Köln",
                  "description": "Zum 1. April.",
                  "keywords": ["Umzug", "Köln"],
                  "relevant_on": "2026-04-01"
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        // Renaming is what this screen is for, so the entry is addressed by id and keeps it --
        // writing the new name would leave the old entry standing beside it.
        assertEquals(entry.toString(), body["id"]!!.jsonPrimitive.content)
        assertEquals("Umzug nach Köln", body["name"]!!.jsonPrimitive.content)
        assertEquals(listOf("umzug", "köln"), body["keywords"]!!.jsonArray.map { it.jsonPrimitive.content })
        // Who wrote the entry does not change by correcting it.
        assertEquals(true, body["created_by_agent"]!!.jsonPrimitive.content.toBoolean())

        val stored = database.query { Knowledges.selectAll().single() }
        assertEquals("Zum 1. April.", stored[Knowledges.description])
        // What the agent's search puts first among equally good hits.
        assertTrue(stored[Knowledges.updatedAt] > before, "updated_at was not bumped")
    }

    @Test
    fun `a date can be taken off an entry again`() = testApplication {
        val user = setUpUser()
        installRoute()
        val entry = knowledgeOf(user, name = "Umzug", byAgent = false)

        val response = client.put("$ROUTE/$entry") {
            contentType(ContentType.Application.Json)
            setBody("""{"name": "Umzug", "description": "Doch nicht.", "relevant_on": null}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(JsonNull, Json.parseToJsonElement(response.bodyAsText()).jsonObject["relevant_on"])
    }

    @Test
    fun `renaming onto a name that is taken is refused`() = testApplication {
        val user = setUpUser()
        installRoute()
        val entry = knowledgeOf(user, name = "Umzug", byAgent = false)
        knowledgeOf(user, name = "Stromvertrag", byAgent = true)

        val response = client.put("$ROUTE/$entry") {
            contentType(ContentType.Application.Json)
            setBody("""{"name": "stromvertrag", "description": "Etwas."}""")
        }

        // The unique index would answer this with a 500; the screen wants to know it was the name.
        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    @Test
    fun `an entry can keep its own name`() = testApplication {
        val user = setUpUser()
        installRoute()
        val entry = knowledgeOf(user, name = "Umzug", byAgent = false)

        val response = client.put("$ROUTE/$entry") {
            contentType(ContentType.Application.Json)
            setBody("""{"name": "Umzug", "description": "Nur der Text ändert sich."}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `deleting takes the row and answers nothing`() = testApplication {
        val user = setUpUser()
        installRoute()
        val entry = knowledgeOf(user, name = "Umzug", byAgent = true)

        assertEquals(HttpStatusCode.NoContent, client.delete("$ROUTE/$entry").status)
        assertEquals(0, database.query { Knowledges.selectAll().count() })

        // The same delete twice is a miss, not a second success.
        assertEquals(HttpStatusCode.NotFound, client.delete("$ROUTE/$entry").status)
    }

    @Test
    fun `somebody else's entry is a miss, for both writes`() = testApplication {
        setUpUser()
        installRoute()

        val stranger = database.query {
            User.new {
                username = "stranger-${Uuid.random()}"
                email = "stranger-${Uuid.random()}@example.com"
                firstname = "Someone"
                lastname = "Else"
            }
        }
        val theirs = knowledgeOf(stranger, name = "Geheimnis", byAgent = true)

        val edit = client.put("$ROUTE/$theirs") {
            contentType(ContentType.Application.Json)
            setBody("""{"name": "Geheimnis", "description": "Übernommen."}""")
        }
        // 404 and not 403: a 403 would confirm the id belongs to somebody.
        assertEquals(HttpStatusCode.NotFound, edit.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("$ROUTE/$theirs").status)

        // And an id that is not an id at all is the same miss.
        assertEquals(HttpStatusCode.NotFound, client.delete("$ROUTE/not-an-id").status)

        assertEquals(1, database.query { Knowledges.selectAll().where { Knowledges.owner eq stranger.id }.count() })
    }

    @Test
    fun `without a session nothing is changed`() = testApplication {
        val user = setUpUser()
        installRoute()
        val entry = knowledgeOf(user, name = "Umzug", byAgent = true)
        signedIn = null

        assertEquals(HttpStatusCode.Unauthorized, client.delete("$ROUTE/$entry").status)
        assertEquals(1, database.query { Knowledges.selectAll().count() })
    }

    private suspend fun knowledgeOf(user: User, name: String, byAgent: Boolean): Uuid = database.query {
        Knowledge.new {
            owner = user
            this.name = name
            description = "Was bisher bekannt war."
            keywords = Knowledge.joinKeywords(listOf(name))
            relevantOn = null
            createdByAgent = byAgent
        }.id.value
    }

    private suspend fun setUpUser(): User {
        database.init()
        database.query { Knowledges.deleteAll() }
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
            installApiErrorHandling()
            install(Authentication) { session() }
            dependencies { provide<OvermailDatabase> { database } }
            routing {
                route("$ROUTE/{knowledgeId}") {
                    updateKnowledgeEntry()
                    deleteKnowledgeEntry()
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
