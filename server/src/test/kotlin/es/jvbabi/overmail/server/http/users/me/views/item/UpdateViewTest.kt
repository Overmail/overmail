package es.jvbabi.overmail.server.http.users.me.views.item

import es.jvbabi.overmail.server.data.notifier.ViewEvent
import es.jvbabi.overmail.server.data.notifier.ViewNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.EmailArchiveAction
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.database.models.View
import es.jvbabi.overmail.server.database.models.ViewSettings
import es.jvbabi.overmail.server.database.models.Views
import es.jvbabi.overmail.server.http.api.installApiErrorHandling
import io.ktor.client.request.patch
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
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respond
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/** Single attributes of a view: its name, where it sits, what it groups and filters by. */
class UpdateViewTest {

    /** Stands in for a label; the route stores what it is given and does not look it up. */
    private val LABEL = Uuid.random()

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:update-view;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private val viewNotifier = ViewNotifier()

    private var signedIn: User? = null

    @Test
    fun `renames a view and leaves the rest alone`() = testApplication {
        val user = setUpUser()
        installRoute()
        val view = addView("First", "a0")

        val response = patchView(view, """{"name":"  Wichtiges  "}""")
        assertEquals(HttpStatusCode.OK, response.status)

        // Trimmed, and reported back as it was stored.
        assertEquals("Wichtiges", body(response)["name"]!!.jsonPrimitive.content)
        assertEquals("Wichtiges", nameOf(view))
        assertEquals("a0", keyOf(view))
    }

    @Test
    fun `moves a view behind another one`() = testApplication {
        val user = setUpUser()
        installRoute()
        val first = addView("First", "a0")
        val second = addView("Second", "a1")
        val third = addView("Third", "a2")

        patchView(first, """{"position":{"after_view_id":"$second"}}""")

        assertEquals(listOf("Second", "First", "Third"), namesInOrder(user))
        // Between its two new neighbours, so nothing else had to be rewritten.
        assertEquals("a1", keyOf(second))
        assertEquals("a2", keyOf(third))
    }

    @Test
    fun `moves a view to the end`() = testApplication {
        val user = setUpUser()
        installRoute()
        val first = addView("First", "a0")
        addView("Second", "a1")
        val third = addView("Third", "a2")

        patchView(first, """{"position":{"after_view_id":"$third"}}""")

        assertEquals(listOf("Second", "Third", "First"), namesInOrder(user))
    }

    @Test
    fun `moves a view to the top`() = testApplication {
        val user = setUpUser()
        installRoute()
        addView("First", "a0")
        addView("Second", "a1")
        val third = addView("Third", "a2")

        // No view to sit behind up there, which is what the null says.
        patchView(third, """{"position":{"after_view_id":null}}""")

        assertEquals(listOf("Third", "First", "Second"), namesInOrder(user))
    }

    @Test
    fun `a view that is already first stays first`() = testApplication {
        val user = setUpUser()
        installRoute()
        val first = addView("First", "a0")
        addView("Second", "a1")

        patchView(first, """{"position":{"after_view_id":null}}""")

        assertEquals(listOf("First", "Second"), namesInOrder(user))
    }

    @Test
    fun `writes the settings as they were sent`() = testApplication {
        setUpUser()
        installRoute()
        val view = addView("First", "a0")

        val response = patchView(
            view,
            """{"view":{"groupings":[{"type":"sender","sort_reversed":true}],""" +
                """"email_sorting":{"type":"subject","sort_reversed":false}}}""",
        )
        assertEquals(HttpStatusCode.OK, response.status)

        val stored = database.query { Views.selectAll().where { Views.id eq view }.single()[Views.view] }
        assertEquals(listOf(ViewSettings.Grouping.SenderGrouping(reversed = true)), stored.groupings)
        assertEquals(ViewSettings.EmailSorting.SubjectSorting(reversed = false), stored.emailSorting)
    }

    @Test
    fun `writes the filter and sends it back`() = testApplication {
        setUpUser()
        installRoute()
        val view = addView("First", "a0")

        val response = patchView(
            view,
            """{"view":{"groupings":[],"filter":{"read_state":false,""" +
                """"archived_state":["Archive","Spam"],"has_labels":["$LABEL"]},""" +
                """"email_sorting":{"type":"date","sort_reversed":false}}}""",
        )
        assertEquals(HttpStatusCode.OK, response.status)

        val stored = database.query { Views.selectAll().where { Views.id eq view }.single()[Views.view] }
        assertEquals(false, stored.filter.readState)
        assertEquals(
            setOf(EmailArchiveAction.Archive, EmailArchiveAction.Spam),
            stored.filter.archivedState,
        )
        assertEquals(setOf(LABEL), stored.filter.hasLabels)
        // Not asked for is not restricted, rather than an empty set, which would let nothing
        // through.
        assertEquals(null, stored.filter.imapAccountIds)

        // The answer carries the filter, so the caller does not have to read it back.
        val filter = response.bodyAsText().let(Json::parseToJsonElement)
            .jsonObject["view"]!!.jsonObject["filter"]!!.jsonObject
        assertEquals(false, filter["read_state"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `settings sent without a filter leave nothing filtered`() = testApplication {
        setUpUser()
        installRoute()
        val view = addView("First", "a0", filter = ViewSettings.Filter(readState = true))

        // The settings are replaced whole, and a filter that is not in them is no filter -- see
        // the route's note on why the object has nothing in it to address on its own.
        patchView(
            view,
            """{"view":{"groupings":[],"email_sorting":{"type":"date","sort_reversed":false}}}""",
        )

        val stored = database.query { Views.selectAll().where { Views.id eq view }.single()[Views.view] }
        assertEquals(ViewSettings.Filter.NONE, stored.filter)
    }

    @Test
    fun `a name and a move in one request`() = testApplication {
        val user = setUpUser()
        installRoute()
        addView("First", "a0")
        val second = addView("Second", "a1")

        patchView(second, """{"name":"Zuerst","position":{"after_view_id":null}}""")

        assertEquals(listOf("Zuerst", "First"), namesInOrder(user))
    }

    @Test
    fun `the change is announced`() = testApplication {
        val user = setUpUser()
        installRoute()
        val view = addView("First", "a0")

        val events = viewNotifier.subscribe(user.id.value)
        coroutineScope {
            val subscribed = CompletableDeferred<Unit>()
            val event = async { events.onSubscription { subscribed.complete(Unit) }.first() }
            subscribed.await()

            patchView(view, """{"name":"Wichtiges"}""")

            assertEquals(ViewEvent.Changed(view), withTimeout(5_000) { event.await() })
        }
    }

    @Test
    fun `a body that says nothing is refused`() = testApplication {
        setUpUser()
        installRoute()
        val view = addView("First", "a0")

        assertEquals(HttpStatusCode.BadRequest, patchView(view, "{}").status)
    }

    @Test
    fun `a blank name is refused`() = testApplication {
        setUpUser()
        installRoute()
        val view = addView("First", "a0")

        assertEquals(HttpStatusCode.BadRequest, patchView(view, """{"name":"   "}""").status)
        assertEquals("First", nameOf(view))
    }

    @Test
    fun `a view cannot be moved behind itself`() = testApplication {
        setUpUser()
        installRoute()
        val view = addView("First", "a0")

        assertEquals(
            HttpStatusCode.BadRequest,
            patchView(view, """{"position":{"after_view_id":"$view"}}""").status,
        )
    }

    @Test
    fun `a neighbour of another user is no neighbour`() = testApplication {
        setUpUser()
        installRoute()
        val view = addView("First", "a0")
        val theirs = addView("Theirs", "a0", owner = otherUser())

        assertEquals(
            HttpStatusCode.NotFound,
            patchView(view, """{"position":{"after_view_id":"$theirs"}}""").status,
        )
        assertEquals("a0", keyOf(view))
    }

    @Test
    fun `a view of another user is not there`() = testApplication {
        setUpUser()
        installRoute()
        val theirs = addView("Theirs", "a0", owner = otherUser())

        assertEquals(HttpStatusCode.NotFound, patchView(theirs, """{"name":"Meins"}""").status)
        assertEquals("Theirs", nameOf(theirs))
    }

    @Test
    fun `an id that is not an id is not there either`() = testApplication {
        setUpUser()
        installRoute()

        val response = client.patch("/api/users/me/views/not-a-uuid") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Meins"}""")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `without a session nothing is written`() = testApplication {
        setUpUser()
        installRoute()
        val view = addView("First", "a0")
        signedIn = null

        assertEquals(HttpStatusCode.Unauthorized, patchView(view, """{"name":"Meins"}""").status)
        assertEquals("First", nameOf(view))
    }

    private suspend fun ApplicationTestBuilder.patchView(viewId: View.Id, body: String) =
        client.patch("/api/users/me/views/$viewId") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun body(response: io.ktor.client.statement.HttpResponse) =
        Json.parseToJsonElement(response.bodyAsText()).jsonObject

    /** The user's views by key, sorted the way the sidebar reads them. */
    private suspend fun namesInOrder(user: User): List<String> = database.query {
        Views
            .selectAll()
            .where { Views.user eq user.id.value }
            .map { row -> row[Views.sortKey] to row[Views.name] }
            .sortedBy { it.first }
            .map { it.second }
    }

    private suspend fun nameOf(viewId: View.Id): String =
        database.query { Views.selectAll().where { Views.id eq viewId }.single()[Views.name] }

    private suspend fun keyOf(viewId: View.Id): String =
        database.query { Views.selectAll().where { Views.id eq viewId }.single()[Views.sortKey] }

    private suspend fun addView(
        name: String,
        sortKey: String,
        owner: User.Id = signedIn!!.id.value,
        filter: ViewSettings.Filter = ViewSettings.Filter.NONE,
    ): View.Id = database.query {
        Views.insertAndGetId {
            it[user] = owner
            it[Views.name] = name
            it[view] = ViewSettings(
                groupings = emptyList(),
                filter = filter,
                emailSorting = ViewSettings.EmailSorting.DateSorting(reversed = false),
            )
            it[Views.sortKey] = sortKey
        }.value
    }

    private suspend fun otherUser(): User.Id = database.query {
        User.new {
            username = "other-${Uuid.random()}"
            email = "other-${Uuid.random()}@example.com"
            firstname = "Other"
            lastname = "User"
        }.id.value
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
                route("/api/users/me/views/{viewId}") { updateView() }
            }
        }
    }

    /** Signed in as whoever [signedIn] holds, like `CreateViewTest`. */
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
