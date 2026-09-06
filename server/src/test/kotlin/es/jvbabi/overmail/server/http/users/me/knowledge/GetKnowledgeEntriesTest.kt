package es.jvbabi.overmail.server.http.users.me.knowledge

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Knowledge
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.http.api.installApiErrorHandling
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
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
import kotlin.test.assertFalse
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database

private const val ROUTE = "/api/users/me/knowledge"

/** What the settings screen lists: this user's knowledge, freshest first. */
class GetKnowledgeEntriesTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:get-knowledge;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private var signedIn: User? = null

    @Test
    fun `lists the entries with everything a row shows, freshest first`() = testApplication {
        val user = setUpUser()
        installRoute()

        val now = Clock.System.now()
        database.query {
            Knowledge.new {
                owner = user
                name = "Stromvertrag"
                description = "Der Nutzer ist bei Rheinenergie."
                keywords = Knowledge.joinKeywords(listOf("Rheinenergie", "Strom"))
                relevantOn = null
                createdByAgent = true
                updatedAt = now - 2.hours
            }
            Knowledge.new {
                owner = user
                name = "Umzug"
                description = "Zieht nach Köln."
                keywords = Knowledge.joinKeywords(listOf("umzug"))
                relevantOn = LocalDate(2026, 4, 1)
                createdByAgent = false
                updatedAt = now
            }
        }

        val response = client.get(ROUTE)
        assertEquals(HttpStatusCode.OK, response.status)

        val entries = Json.parseToJsonElement(response.bodyAsText()).jsonObject["knowledge"]!!.jsonArray
        assertEquals(listOf("Umzug", "Stromvertrag"), entries.map { it.jsonObject["name"]!!.jsonPrimitive.content })

        val umzug = entries[0].jsonObject
        assertEquals("Zieht nach Köln.", umzug["description"]!!.jsonPrimitive.content)
        assertEquals("2026-04-01", umzug["relevant_on"]!!.jsonPrimitive.content)
        assertEquals(false, umzug["created_by_agent"]!!.jsonPrimitive.content.toBoolean())

        val strom = entries[1].jsonObject
        // Stored as one comma-separated column, handed out as the list the screen renders.
        assertEquals(
            listOf("rheinenergie", "strom"),
            strom["keywords"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        // Most knowledge is not about a day, and the screen has to be able to tell.
        assertEquals(JsonNull, strom["relevant_on"])
        assertEquals(true, strom["created_by_agent"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `somebody else's knowledge is not listed`() = testApplication {
        setUpUser()
        installRoute()

        database.query {
            val stranger = User.new {
                username = "stranger-${Uuid.random()}"
                email = "stranger-${Uuid.random()}@example.com"
                firstname = "Someone"
                lastname = "Else"
            }
            Knowledge.new {
                owner = stranger
                name = "Geheimnis"
                description = "Nicht für andere Augen."
                keywords = ""
                relevantOn = null
                createdByAgent = true
            }
        }

        val body = client.get(ROUTE).bodyAsText()
        assertFalse(body.contains("Geheimnis"), body)
    }

    @Test
    fun `a user the assistant knows nothing about gets an empty list, not an error`() = testApplication {
        setUpUser()
        installRoute()

        val response = client.get(ROUTE)

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(0, Json.parseToJsonElement(response.bodyAsText()).jsonObject["knowledge"]!!.jsonArray.size)
    }

    @Test
    fun `without a session there is nothing to list`() = testApplication {
        setUpUser()
        signedIn = null
        installRoute()

        assertEquals(HttpStatusCode.Unauthorized, client.get(ROUTE).status)
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
            installApiErrorHandling()
            install(Authentication) { session() }
            dependencies { provide<OvermailDatabase> { database } }
            routing {
                route(ROUTE) { getKnowledgeEntries() }
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
