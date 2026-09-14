package es.jvbabi.overmail.server.http.users.me.views

import es.jvbabi.overmail.server.data.notifier.ViewEvent
import es.jvbabi.overmail.server.data.notifier.ViewNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.database.models.ViewSettings
import es.jvbabi.overmail.server.database.models.Views
import es.jvbabi.overmail.server.database.models.viewSortKeyAfter
import es.jvbabi.overmail.server.http.api.installApiErrorHandling
import io.ktor.client.request.header
import io.ktor.client.request.post
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
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respond
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class CreateViewTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:create-view;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private val viewNotifier = ViewNotifier()

    private var signedIn: User? = null

    @Test
    fun `names the first view in the language of the ui`() = testApplication {
        setUpUser()
        installRoute()

        val response = client.post("/api/users/me/views/new") {
            header(HttpHeaders.Cookie, "$LOCALE_COOKIE=de")
        }
        assertEquals(HttpStatusCode.Created, response.status)

        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Neue Ansicht 1", body["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the counter climbs with what is already there`() = testApplication {
        setUpUser()
        installRoute()

        val names = (1..3).map {
            val response = client.post("/api/users/me/views/new") {
                header(HttpHeaders.Cookie, "$LOCALE_COOKIE=de")
            }
            Json.parseToJsonElement(response.bodyAsText()).jsonObject["name"]!!.jsonPrimitive.content
        }

        assertEquals(listOf("Neue Ansicht 1", "Neue Ansicht 2", "Neue Ansicht 3"), names)
    }

    @Test
    fun `a freed number is used again rather than skipped`() = testApplication {
        val user = setUpUser()
        installRoute()

        repeat(3) { client.post("/api/users/me/views/new") { header(HttpHeaders.Cookie, "$LOCALE_COOKIE=de") } }
        database.query {
            Views.deleteWhere { (Views.user eq user.id.value) and (Views.name eq "Neue Ansicht 2") }
        }

        val response = client.post("/api/users/me/views/new") {
            header(HttpHeaders.Cookie, "$LOCALE_COOKIE=de")
        }
        val name = Json.parseToJsonElement(response.bodyAsText()).jsonObject["name"]!!.jsonPrimitive.content
        assertEquals("Neue Ansicht 2", name)
    }

    @Test
    fun `each language counts on its own`() = testApplication {
        setUpUser()
        installRoute()

        repeat(2) { client.post("/api/users/me/views/new") { header(HttpHeaders.Cookie, "$LOCALE_COOKIE=de") } }

        // German names do not occupy English numbers: switching the ui starts a fresh count.
        val english = client.post("/api/users/me/views/new") {
            header(HttpHeaders.Cookie, "$LOCALE_COOKIE=en")
        }
        assertEquals(
            "New view 1",
            Json.parseToJsonElement(english.bodyAsText()).jsonObject["name"]!!.jsonPrimitive.content,
        )

        // And the German count carries on where it was.
        val german = client.post("/api/users/me/views/new") {
            header(HttpHeaders.Cookie, "$LOCALE_COOKIE=de")
        }
        assertEquals(
            "Neue Ansicht 3",
            Json.parseToJsonElement(german.bodyAsText()).jsonObject["name"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `without a cookie the browser's accept-language decides`() = testApplication {
        setUpUser()
        installRoute()

        val response = client.post("/api/users/me/views/new") {
            header(HttpHeaders.AcceptLanguage, "de-DE,de;q=0.9,en;q=0.8")
        }
        assertEquals(
            "Neue Ansicht 1",
            Json.parseToJsonElement(response.bodyAsText()).jsonObject["name"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `an unknown language falls back to english`() = testApplication {
        setUpUser()
        installRoute()

        val response = client.post("/api/users/me/views/new") {
            header(HttpHeaders.AcceptLanguage, "fr-FR,fr;q=0.9")
        }
        assertEquals(
            "New view 1",
            Json.parseToJsonElement(response.bodyAsText()).jsonObject["name"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `the view is created empty and readable back out of the database`() = testApplication {
        val user = setUpUser()
        installRoute()

        val response = client.post("/api/users/me/views/new")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        // The settings survive the jsonb round trip, which is the part the sealed
        // EmailSorting hierarchy has to be annotated for.
        val stored = database.query {
            Views.selectAll().where { Views.user eq user.id.value }.single()
        }
        assertEquals(emptyList<ViewSettings.Grouping>(), stored[Views.view].groupings)
        assertEquals(
            ViewSettings.EmailSorting.DateSorting(reversed = false),
            stored[Views.view].emailSorting,
        )
        // Empty includes the filter: a new view leaves nothing out of the listing.
        assertEquals(ViewSettings.Filter.NONE, stored[Views.view].filter)
        assertEquals(stored[Views.id].value.toString(), body["id"]!!.jsonPrimitive.content)
        assertEquals(stored[Views.name], body["name"]!!.jsonPrimitive.content)

        // And the answer carries them, so the caller can render the row without a second request.
        val view = body["view"]!!.jsonObject
        assertTrue(view.containsKey("groupings"), view.toString())
        assertTrue(view.containsKey("filter"), view.toString())
        assertTrue(view.containsKey("email_sorting"), view.toString())
    }

    @Test
    fun `views of another user do not count`() = testApplication {
        setUpUser()
        installRoute()

        val other = database.query {
            User.new {
                username = "other-${Uuid.random()}"
                email = "other-${Uuid.random()}@example.com"
                firstname = "Other"
                lastname = "User"
            }
        }
        database.query {
            Views.insert {
                it[user] = other.id.value
                it[name] = "Neue Ansicht 1"
                it[view] = ViewSettings(
                    groupings = emptyList(),
                    emailSorting = ViewSettings.EmailSorting.DateSorting(reversed = false),
                )
            }
        }

        val response = client.post("/api/users/me/views/new") {
            header(HttpHeaders.Cookie, "$LOCALE_COOKIE=de")
        }
        assertEquals(
            "Neue Ansicht 1",
            Json.parseToJsonElement(response.bodyAsText()).jsonObject["name"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `the views stand in the order they were created in`() = testApplication {
        val user = setUpUser()
        installRoute()

        repeat(3) { client.post("/api/users/me/views/new") { header(HttpHeaders.Cookie, "$LOCALE_COOKIE=de") } }

        assertEquals(listOf("Neue Ansicht 1", "Neue Ansicht 2", "Neue Ansicht 3"), namesInOrder(user))
    }

    @Test
    fun `a refilled number lands back in its gap instead of at the end`() = testApplication {
        val user = setUpUser()
        installRoute()

        repeat(3) { client.post("/api/users/me/views/new") { header(HttpHeaders.Cookie, "$LOCALE_COOKIE=de") } }
        database.query {
            Views.deleteWhere { (Views.user eq user.id.value) and (Views.name eq "Neue Ansicht 2") }
        }

        // Behind "Neue Ansicht 1", which is where the number says it belongs -- not behind 3.
        client.post("/api/users/me/views/new") { header(HttpHeaders.Cookie, "$LOCALE_COOKIE=de") }

        assertEquals(listOf("Neue Ansicht 1", "Neue Ansicht 2", "Neue Ansicht 3"), namesInOrder(user))
    }

    @Test
    fun `a view whose predecessor moved follows it rather than the list`() = testApplication {
        val user = setUpUser()
        installRoute()

        repeat(3) { client.post("/api/users/me/views/new") { header(HttpHeaders.Cookie, "$LOCALE_COOKIE=de") } }
        // What dragging "Neue Ansicht 1" to the bottom does: only its own key is rewritten.
        database.query {
            val keys = Views.selectAll().where { Views.user eq user.id.value }.map { row -> row[Views.sortKey] }
            Views.update({ (Views.user eq user.id.value) and (Views.name eq "Neue Ansicht 1") }) {
                it[sortKey] = viewSortKeyAfter(keys, null)
            }
        }

        // Behind "Neue Ansicht 3", which is no longer the last row.
        client.post("/api/users/me/views/new") { header(HttpHeaders.Cookie, "$LOCALE_COOKIE=de") }

        assertEquals(
            listOf("Neue Ansicht 2", "Neue Ansicht 3", "Neue Ansicht 4", "Neue Ansicht 1"),
            namesInOrder(user),
        )
    }

    @Test
    fun `a view without a predecessor goes to the end`() = testApplication {
        val user = setUpUser()
        installRoute()

        repeat(2) { client.post("/api/users/me/views/new") { header(HttpHeaders.Cookie, "$LOCALE_COOKIE=de") } }
        // "New view 1" is a number 1: nothing to sit behind, so it appends.
        client.post("/api/users/me/views/new") { header(HttpHeaders.Cookie, "$LOCALE_COOKIE=en") }

        assertEquals(listOf("Neue Ansicht 1", "Neue Ansicht 2", "New view 1"), namesInOrder(user))
    }

    @Test
    fun `the answer carries the key the row was written with`() = testApplication {
        val user = setUpUser()
        installRoute()

        val response = client.post("/api/users/me/views/new")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        val stored = database.query {
            Views.selectAll().where { Views.user eq user.id.value }.single()
        }
        assertEquals(stored[Views.sortKey], body["sort_key"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the created view is announced`() = testApplication {
        val user = setUpUser()
        installRoute()

        val events = viewNotifier.subscribe(user.id.value)
        coroutineScope {
            // The notifier keeps no history, so the collector has to be attached before the
            // request -- an event fired in between would reach nobody.
            val subscribed = CompletableDeferred<Unit>()
            val event = async { events.onSubscription { subscribed.complete(Unit) }.first() }
            subscribed.await()

            val response = client.post("/api/users/me/views/new")
            val id = Json.parseToJsonElement(response.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

            assertEquals(ViewEvent.Changed(Uuid.parse(id)), withTimeout(5_000) { event.await() })
        }
    }

    @Test
    fun `without a session nothing is created`() = testApplication {
        setUpUser()
        signedIn = null
        installRoute()

        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/users/me/views/new").status)
    }

    /** The user's views by key, sorted the way the list is meant to read. */
    private suspend fun namesInOrder(user: User): List<String> = database.query {
        Views
            .selectAll()
            .where { Views.user eq user.id.value }
            .map { row -> row[Views.sortKey] to row[Views.name] }
            .sortedBy { it.first }
            .map { it.second }
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
            dependencies {
                provide<OvermailDatabase> { database }
                provide<ViewNotifier> { viewNotifier }
            }
            routing {
                route("/api/users/me/views/new") { createView() }
            }
        }
    }

    /** Signed in as whoever [signedIn] holds, like `GetCurrentUserTest`. */
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
