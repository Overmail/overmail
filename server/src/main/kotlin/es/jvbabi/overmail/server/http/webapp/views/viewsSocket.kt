package es.jvbabi.overmail.server.http.webapp.views

import es.jvbabi.overmail.server.data.notifier.ViewNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.View
import es.jvbabi.overmail.server.database.models.ViewSettings
import es.jvbabi.overmail.server.database.models.Views
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUser
import es.jvbabi.overmail.server.http.clientWebSocket
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.websocket.sendSerialized
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * The user's views, as the sidebar lists them: `GET /api/webapp/views/socket`.
 *
 * The whole list, on connect and again whenever anything about a view changed -- created,
 * renamed, reconfigured, moved, deleted. A user has a handful of views and each is a name and a
 * settings object, so sending all of them is cheaper than working out what a client is missing,
 * and a client that lost a message is right again on the next one instead of staying wrong until
 * it reconnects. Only an actual change goes out.
 *
 * Read-only. The writes are the api routes under `/api/users/me/views`, which announce themselves
 * through [ViewNotifier] and reach every open socket of that user -- including the other tabs.
 */
fun Route.viewsSocket() {
    authenticate {
        clientWebSocket {
            val database = application.dependencies.resolve<OvermailDatabase>()
            val viewNotifier = application.dependencies.resolve<ViewNotifier>()
            val user = call.requireAuthenticatedUser()

            var sent: ViewsServerMessage.ViewList? = null

            suspend fun sendViews() {
                val views = database.query {
                    Views
                        .selectAll()
                        .where { Views.user eq user.id }
                        .map { row ->
                            ViewEntry(
                                id = row[Views.id].value,
                                name = row[Views.name],
                                sortKey = row[Views.sortKey],
                                view = row[Views.view],
                            )
                        }
                }

                // Sorted here and not by the database: the keys are fractional indexes, which
                // order by character code, and Postgres' default collation does not -- see
                // `Views.sortKey`.
                val message = ViewsServerMessage.ViewList(views.sortedBy { entry -> entry.sortKey })
                // Unchanged happens: an event about a view says something moved, not that what
                // this socket shows of it is different.
                if (message == sent) return
                sent = message
                sendSerialized<ViewsServerMessage>(message)
            }

            // The first read happens from onSubscription, so it cannot fall into the gap between
            // reading and listening: an event fired in there would reach nobody, and the client
            // would sit on a stale list until it reconnects. Subscribed first, the event is
            // buffered and the collector below reads again.
            launch {
                viewNotifier.subscribe(user.id.value)
                    .onSubscription { sendViews() }
                    // collectLatest, so a burst -- a tab creating views, a reorder writing a row
                    // per view -- is read once at the end instead of once per event.
                    .collectLatest { sendViews() }
            }

            // Nothing is expected from the client; this keeps the connection open until it goes
            // away, which is what ends the subscription above with it.
            for (frame in incoming) Unit
        }
    }
}

@Serializable
private sealed class ViewsServerMessage {
    /** Every view the user has, in the order the sidebar shows them. */
    @Serializable
    @SerialName("data.views")
    data class ViewList(@SerialName("views") val views: List<ViewEntry>) : ViewsServerMessage()
}

@Serializable
private data class ViewEntry(
    @SerialName("id") val id: View.Id,
    @SerialName("name") val name: String,
    /** What the order above is, so a client can place a view it creates itself. */
    @SerialName("sort_key") val sortKey: String,
    @SerialName("view") val view: ViewSettings,
)
