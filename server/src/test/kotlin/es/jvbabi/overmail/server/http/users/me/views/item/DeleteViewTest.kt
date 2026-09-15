package es.jvbabi.overmail.server.http.users.me.views.item

import es.jvbabi.overmail.server.data.notifier.ViewEvent
import es.jvbabi.overmail.server.data.notifier.ViewNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.database.models.View
import es.jvbabi.overmail.server.database.models.ViewSettings
import es.jvbabi.overmail.server.database.models.Views
import es.jvbabi.overmail.server.http.api.installApiErrorHandling
import io.ktor.client.request.delete
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/** Taking a view away: the row goes, the ones around it stay where the user put them. */
class DeleteViewTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:delete-view;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private val viewNotifier = ViewNotifier()

    private var signedIn: User? = null

    @Test
    fun `deletes the view and leaves the others alone`() = testApplication {
        val user = setUpUser()
        installRoute()
        val first = addView("First", "a0")
        addView("Second", "a1")

        assertEquals(HttpStatusCode.NoContent, deleteView(first).status)

        assertEquals(listOf("Second"), namesInOrder(user))
        // The gap in the keys is no gap in the list: they are fractional, and rewriting the
        // others would mean touching every row for a delete.
        assertEquals("a1", keyOf(view = "Second", user = user))
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

            deleteView(view)

            assertEquals(ViewEvent.Changed(view), withTimeout(5_000) { event.await() })
        }
    }

    @Test
    fun `a view that is gone is not there a second time`() = testApplication {
        setUpUser()
        installRoute()
        val view = addView("First", "a0")

        assertEquals(HttpStatusCode.NoContent, deleteView(view).status)
        assertEquals(HttpStatusCode.NotFound, deleteView(view).status)
    }

    @Test
    fun `a view of another user is not there`() = testApplication {
        setUpUser()
        installRoute()
        val theirs = addView("Theirs", "a0", owner = otherUser())

        assertEquals(HttpStatusCode.NotFound, deleteView(theirs).status)
        assertEquals(1, countOf(theirs))
    }

    @Test
    fun `an id that is not an id is not there either`() = testApplication {
        setUpUser()
        installRoute()

        assertEquals(HttpStatusCode.NotFound, client.delete("/api/users/me/views/not-a-uuid").status)
    }

    @Test
    fun `without a session nothing is deleted`() = testApplication {
        setUpUser()
        installRoute()
        val view = addView("First", "a0")
        signedIn = null

        assertEquals(HttpStatusCode.Unauthorized, deleteView(view).status)
        assertEquals(1, countOf(view))
    }

    private suspend fun ApplicationTestBuilder.deleteView(viewId: View.Id) =
        client.delete("/api/users/me/views/$viewId")

    /** The user's views by key, sorted the way the sidebar reads them. */
    private suspend fun namesInOrder(user: User): List<String> = database.query {
        Views
            .selectAll()
            .where { Views.user eq user.id.value }
            .map { row -> row[Views.sortKey] to row[Views.name] }
            .sortedBy { it.first }
            .map { it.second }
    }

    private suspend fun keyOf(view: String, user: User): String = database.query {
        Views
            .selectAll()
            .where { (Views.user eq user.id.value) and (Views.name eq view) }
            .single()[Views.sortKey]
    }

    private suspend fun countOf(viewId: View.Id): Long =
        database.query { Views.selectAll().where { Views.id eq viewId }.count() }

    private suspend fun addView(
        name: String,
        sortKey: String,
        owner: User.Id = signedIn!!.id.value,
    ): View.Id = database.query {
        Views.insertAndGetId {
            it[user] = owner
            it[Views.name] = name
            it[view] = ViewSettings(
                groupings = emptyList(),
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
                route("/api/users/me/views/{viewId}") { deleteView() }
            }
        }
    }

    /** Signed in as whoever [signedIn] holds, like `UpdateViewTest`. */
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
