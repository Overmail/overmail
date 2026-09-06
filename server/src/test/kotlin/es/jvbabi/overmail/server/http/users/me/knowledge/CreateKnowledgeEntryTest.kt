package es.jvbabi.overmail.server.http.users.me.knowledge

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Knowledge
import es.jvbabi.overmail.server.database.models.Knowledges
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.http.api.installApiErrorHandling
import io.ktor.client.request.post
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
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll

private const val ROUTE = "/api/users/me/knowledge"

/** Knowledge the user writes themselves, rather than the assistant picking it up. */
class CreateKnowledgeEntryTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:create-knowledge;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private var signedIn: User? = null

    @Test
    fun `writes the entry, normalized the way the assistant's own writes are`() = testApplication {
        val user = setUpUser()
        installRoute()

        val response = client.post(ROUTE) {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "name": "  Strom   vertrag ",
                  "description": "  Bei Rheinenergie.  ",
                  "keywords": ["Rheinenergie", " STROM ", "rheinenergie", ""],
                  "relevant_on": "2026-04-01"
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Strom vertrag", body["name"]!!.jsonPrimitive.content)
        assertEquals("Bei Rheinenergie.", body["description"]!!.jsonPrimitive.content)
        // Lowercased, trimmed and deduplicated, so a keyword typed here and one the agent wrote
        // are looked up by the same rules.
        assertEquals(
            listOf("rheinenergie", "strom"),
            body["keywords"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("2026-04-01", body["relevant_on"]!!.jsonPrimitive.content)
        // The screen tells the two apart, so this is the one thing the route decides itself.
        assertEquals(false, body["created_by_agent"]!!.jsonPrimitive.content.toBoolean())

        val stored = database.query {
            Knowledges.selectAll().where { Knowledges.owner eq user.id }.single()
        }
        assertEquals("Strom vertrag", stored[Knowledges.name])
        assertEquals("rheinenergie,strom", stored[Knowledges.keywords])
    }

    @Test
    fun `a name that is already taken is refused rather than overwriting what is there`() = testApplication {
        val user = setUpUser()
        installRoute()

        database.query {
            Knowledge.new {
                owner = user
                name = "Stromvertrag"
                description = "Was der Agent gelernt hat."
                keywords = "strom"
                relevantOn = null
                createdByAgent = true
            }
        }

        // Differently capitalized: the same entry to everything that reads it.
        val response = client.post(ROUTE) {
            contentType(ContentType.Application.Json)
            setBody("""{"name": "stromvertrag", "description": "Etwas anderes."}""")
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals(
            "Was der Agent gelernt hat.",
            database.query { Knowledges.selectAll().single()[Knowledges.description] },
        )
    }

    @Test
    fun `an entry without a name or a description is not an entry`() = testApplication {
        setUpUser()
        installRoute()

        val nameless = client.post(ROUTE) {
            contentType(ContentType.Application.Json)
            setBody("""{"name": "   ", "description": "Etwas."}""")
        }
        assertEquals(HttpStatusCode.BadRequest, nameless.status)

        val empty = client.post(ROUTE) {
            contentType(ContentType.Application.Json)
            setBody("""{"name": "Umzug", "description": " "}""")
        }
        assertEquals(HttpStatusCode.BadRequest, empty.status)

        val notADate = client.post(ROUTE) {
            contentType(ContentType.Application.Json)
            setBody("""{"name": "Umzug", "description": "Nach Köln.", "relevant_on": "irgendwann"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, notADate.status)

        assertEquals(0, database.query { Knowledges.selectAll().count() })
    }

    @Test
    fun `without a session nothing is written`() = testApplication {
        setUpUser()
        signedIn = null
        installRoute()

        val response = client.post(ROUTE) {
            contentType(ContentType.Application.Json)
            setBody("""{"name": "Umzug", "description": "Nach Köln."}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(0, database.query { Knowledges.selectAll().count() })
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
                route(ROUTE) { createKnowledgeEntry() }
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
