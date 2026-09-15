package es.jvbabi.overmail.server.http.webapp.views

import es.jvbabi.overmail.server.data.notifier.ViewNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.database.models.View
import es.jvbabi.overmail.server.database.models.ViewSettings
import es.jvbabi.overmail.server.database.models.Views
import es.jvbabi.overmail.server.database.models.viewSortKeyAfter
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/** The sidebar's live list of views. */
class ViewsSocketTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:views-socket;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private val viewNotifier = ViewNotifier()

    private lateinit var signedIn: User

    @Test
    fun `sends the views in sort key order on connect`() = testApplication {
        setUp()
        installRoute()
        // Inserted out of order on purpose: what the socket sends is the key order, not the
        // insertion order.
        val second = addView("Second", "a1")
        val first = addView("First", "a0")

        val socket = openSocket()
        assertEquals(listOf(first, second), socket.nextViews().map { it.id })
        socket.close()
    }

    @Test
    fun `a view of another user is none of this socket's business`() = testApplication {
        setUp()
        installRoute()
        val mine = addView("Mine", "a0")
        addView("Theirs", "a0", owner = otherUser())

        val socket = openSocket()
        assertEquals(listOf(mine), socket.nextViews().map { it.id })
        socket.close()
    }

    @Test
    fun `a created view arrives without asking`() = testApplication {
        setUp()
        installRoute()
        addView("First", "a0")

        val socket = openSocket()
        assertEquals(listOf("First"), socket.nextViews().map { it.name })

        val created = addView("Second", "a1")
        viewNotifier.notifyViewChanged(signedIn.id.value, created)

        assertEquals(listOf("First", "Second"), socket.nextViews().map { it.name })
        socket.close()
    }

    @Test
    fun `a rename and a move are sent again`() = testApplication {
        setUp()
        installRoute()
        val first = addView("First", "a0")
        val second = addView("Second", "a1")

        val socket = openSocket()
        assertEquals(listOf("First", "Second"), socket.nextViews().map { it.name })

        rename(first, "Renamed")
        viewNotifier.notifyViewChanged(signedIn.id.value, first)
        assertEquals(listOf("Renamed", "Second"), socket.nextViews().map { it.name })

        // Behind "Second", which is what dragging it down writes.
        move(first, viewSortKeyAfter(listOf("a0", "a1"), null))
        viewNotifier.notifyViewChanged(signedIn.id.value, first)
        assertEquals(listOf("Second", "Renamed"), socket.nextViews().map { it.name })

        remove(second)
        viewNotifier.notifyViewChanged(signedIn.id.value, second)
        assertEquals(listOf("Renamed"), socket.nextViews().map { it.name })
        socket.close()
    }

    @Test
    fun `an event that changes nothing sends nothing`() = testApplication {
        setUp()
        installRoute()
        val view = addView("First", "a0")

        val socket = openSocket()
        assertEquals(listOf("First"), socket.nextViews().map { it.name })

        // Announced without a write behind it, which is what a deletion of somebody else's view
        // or a repeated write looks like from here.
        viewNotifier.notifyViewChanged(signedIn.id.value, view)

        assertNull(withTimeoutOrNull(2_000) { socket.nextViewsOrNull() })
        socket.close()
    }

    @Test
    fun `the settings travel with the view`() = testApplication {
        setUp()
        installRoute()
        addView(
            "First",
            "a0",
            settings = ViewSettings(
                groupings = listOf(ViewSettings.Grouping.SenderGrouping(reversed = true)),
                emailSorting = ViewSettings.EmailSorting.SubjectSorting(reversed = false),
            ),
        )

        val socket = openSocket()
        val view = socket.nextViewMessages().single()
        assertEquals("sender", view["view"]!!.jsonObject["groupings"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("a0", view["sort_key"]!!.jsonPrimitive.content)
        socket.close()
    }

    private suspend fun ApplicationTestBuilder.openSocket(): WebSocketSession =
        createClient { install(ClientWebSockets) }.webSocketSession("/api/webapp/views/socket")

    /** The views of the next list message: id and name, in the order they were sent in. */
    private suspend fun WebSocketSession.nextViews(): List<SentView> =
        nextViewMessages().map { view ->
            SentView(
                id = Uuid.parse(view["id"]!!.jsonPrimitive.content),
                name = view["name"]!!.jsonPrimitive.content,
            )
        }

    private suspend fun WebSocketSession.nextViewMessages(): List<JsonObject> =
        withTimeout(5_000) { requireNotNull(nextViewsOrNull()) { "no data.views from the socket" } }
            .map { element -> element.jsonObject }

    private suspend fun WebSocketSession.nextViewsOrNull(): List<JsonElement>? {
        for (frame in incoming) {
            val text = (frame as? Frame.Text ?: continue).readText()
            val message = Json.parseToJsonElement(text).jsonObject
            if (message["type"]!!.jsonPrimitive.content != "data.views") continue
            return message["views"]!!.jsonArray
        }
        return null
    }

    private data class SentView(val id: View.Id, val name: String)

    private suspend fun addView(
        name: String,
        sortKey: String,
        owner: User.Id = signedIn.id.value,
        settings: ViewSettings = ViewSettings(
            groupings = emptyList(),
            emailSorting = ViewSettings.EmailSorting.DateSorting(reversed = false),
        ),
    ): View.Id = database.query {
        Views.insertAndGetId {
            it[user] = owner
            it[Views.name] = name
            it[view] = settings
            it[Views.sortKey] = sortKey
        }.value
    }

    private suspend fun rename(viewId: View.Id, name: String) {
        database.query { Views.update({ Views.id eq viewId }) { it[Views.name] = name } }
    }

    private suspend fun move(viewId: View.Id, sortKey: String) {
        database.query { Views.update({ Views.id eq viewId }) { it[Views.sortKey] = sortKey } }
    }

    private suspend fun remove(viewId: View.Id) {
        database.query { Views.deleteWhere { Views.id eq viewId } }
    }

    private suspend fun otherUser(): User.Id = database.query {
        User.new {
            username = "other-${Uuid.random()}"
            email = "other-${Uuid.random()}@example.com"
            firstname = "Other"
            lastname = "User"
        }.id.value
    }

    /** A user of their own per test, and nothing left over from the last one. */
    private suspend fun setUp() {
        database.init()
        signedIn = database.query {
            User.new {
                username = "owner-${Uuid.random()}"
                email = "owner-${Uuid.random()}@example.com"
                firstname = "Julius"
                lastname = "Babies"
            }
        }
    }

    private fun ApplicationTestBuilder.installRoute() {
        application {
            install(WebSockets) {
                contentConverter = KotlinxWebsocketSerializationConverter(Json { encodeDefaults = true })
            }
            install(Authentication) { alwaysSignedIn() }
            dependencies {
                provide<OvermailDatabase> { database }
                provide<ViewNotifier> { viewNotifier }
            }
            routing {
                route("/api/webapp/views/socket") { viewsSocket() }
            }
        }
    }

    private fun AuthenticationConfig.alwaysSignedIn() =
        register(object : AuthenticationProvider(TestConfig()) {
            override suspend fun onAuthenticate(context: AuthenticationContext) {
                context.principal(signedIn)
            }
        })

    private class TestConfig : AuthenticationProvider.Config(null)
}
