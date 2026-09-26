package es.jvbabi.overmail.server.http.email.list

import es.jvbabi.overmail.server.data.notifier.MailEvent
import es.jvbabi.overmail.server.data.notifier.MailNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.http.api.ApiErrorBody
import es.jvbabi.overmail.server.http.api.ApiException
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.dependency
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.Route
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and

private val json = Json { encodeDefaults = true }

/**
 * How long a quiet stream waits before it writes a comment. Only a write finds out that the client
 * went away -- without it, a stream nobody reads any more would sit waiting for the next change.
 */
private val KEEP_ALIVE = 15.seconds

/**
 * Every mail id of a listing, per group, kept current: `GET /api/emails/list/ids/stream?by=…`.
 *
 * What a client with a cache of its own needs: not a window of the listing like the listing
 * socket, but which mails are in it at all, so it can load the ones it lacks and drop the ones
 * that left. A `snapshot` opens the stream and follows whenever this user's mail changes the
 * answer -- always the whole listing, never a patch, so a client that missed one is corrected by
 * the next.
 *
 * Per group because each is cut at [MAX_IDS], like `/ids` -- the same function answers both.
 */
fun Route.emailListIdsStream(keepAlive: Duration = KEEP_ALIVE) {
    authenticate {
        /**
         * Follow every mail id of a listing, per group.
         *
         * Description: Server-sent events. A `snapshot` of every group with its ids first, newest first and at most 10000 per group, then another one whenever the answer changed. A parameter that cannot be read is a `failed` event, and the stream ends. A filter parameter that is absent restricts nothing; one that is present but empty lets nothing through.
         *
         * Tag: Listing
         *
         * Query parameters:
         *   - by [String] Comma-separated groupings, outermost first: `date_smart`, `year`, `month`, `day`, `sender`, `imap_account`, `read`, `archived`
         *   - archived_state [String] Comma-separated states that pass: `Archive`, `Unarchive`, `Spam`
         *   - read_state [Boolean] `true` for read mails only, `false` for unread ones only
         *   - imap_account_ids [String] Comma-separated ids of the inboxes the mails came in through
         *   - sent_by [String] Comma-separated sender ids; `self` stands for the user's own addresses
         *   - sent_to [String] Comma-separated recipient ids; `self` stands for the user's own addresses
         *   - has_labels [String] Comma-separated label ids; a mail has to carry at least one of them
         *
         * Responses:
         *   - 200 text/event-stream [IdsStreamEvent] The events
         */
        sse {
            // Read inside the stream rather than before it: once an EventSource is open there is
            // no status left to answer with, so a parameter it cannot read becomes an event.
            val listing = try {
                val parameters = call.request.queryParameters
                WatchedIds(groupings = mailGroupings(parameters), filter = mailFilter(parameters))
            } catch (e: ApiException) {
                send(IdsStreamEvent.Failed(e.toBody().error))
                return@sse
            }

            follow(
                listing = listing,
                userId = call.requireAuthenticatedUserId(),
                database = call.database(),
                mailNotifier = call.dependency<MailNotifier>(),
                keepAlive = keepAlive,
            )
        }
    }
}

/** The listing a stream is about, read once so every snapshot of it is cut the same way. */
private class WatchedIds(val groupings: List<MailGroupingKind>, val filter: MailFilter) {
    /**
     * Whether a change that moved no mail can still change what this listing holds. The notifier
     * leaves the read state and the labels out of a move, as they move nothing in a listing that
     * does not ask for them -- one that filters or groups by them does.
     */
    val readsFlags: Boolean =
        filter.readState != null || filter.hasLabels != null || MailGroupingKind.READ in groupings
}

/** Sends a snapshot now and again whenever one would be different, until the client leaves. */
private suspend fun ServerSSESession.follow(
    listing: WatchedIds,
    userId: User.Id,
    database: OvermailDatabase,
    mailNotifier: MailNotifier,
    keepAlive: Duration,
) {
    /** That something changed. Conflated: a burst of them is one token and one snapshot. */
    val changed = Channel<Unit>(Channel.CONFLATED)

    // The subscription is up before the first snapshot is read, so a change that happens while it
    // is being loaded is not lost: it either made it into that snapshot or it is sitting in
    // [changed] and the listing is read again.
    val listening = CompletableDeferred<Unit>()
    launch {
        mailNotifier.subscribe(userId)
            .onSubscription { listening.complete(Unit) }
            .collect { event ->
                if (event is MailEvent.Changed && (event.movedListings || listing.readsFlags)) {
                    changed.trySend(Unit)
                }
            }
    }
    listening.await()

    // An event says that a mail changed, not that the ids did -- a mail archived out of a
    // listing that shows the archive moves nothing in it.
    var last: IdsStreamEvent.Snapshot? = null
    while (true) {
        val snapshot = database.query { idsSnapshot(userId, listing) }
        if (snapshot != last) {
            send(snapshot)
            last = snapshot
        }

        while (withTimeoutOrNull(keepAlive) { changed.receive() } == null) {
            send(ServerSentEvent(comments = "keep-alive"))
        }
    }
}

/** Every group of [listing] with its ids, from the same functions `/groups` and `/ids` answer from. */
private fun idsSnapshot(userId: User.Id, listing: WatchedIds): IdsStreamEvent.Snapshot {
    val shape = listingGroups(userId, listing.filter, listing.groupings)

    return IdsStreamEvent.Snapshot(
        groupings = shape.groupings,
        groups = shape.groups.map { group ->
            var predicate: Op<Boolean> = Op.TRUE
            for ((kind, key) in listing.groupings.zip(group.keys)) predicate = predicate and kind.groupPredicate(key)

            val ids = listingIds(userId, listing.filter, predicate)
            IdsStreamGroup(keys = group.keys, total = ids.total, ids = ids.ids)
        },
    )
}

private suspend fun ServerSSESession.send(event: IdsStreamEvent) =
    send(ServerSentEvent(data = json.encodeToString<IdsStreamEvent>(event)))

@Serializable
internal sealed class IdsStreamEvent {

    /** Every group of the listing with its ids, replacing what the client has. */
    @Serializable
    @SerialName("snapshot")
    data class Snapshot(
        @SerialName("groupings") val groupings: List<String>,
        @SerialName("groups") val groups: List<IdsStreamGroup>,
    ) : IdsStreamEvent()

    /** A parameter could not be read. The same body the endpoints answer with; the stream ends. */
    @Serializable
    @SerialName("failed")
    data class Failed(@SerialName("error") val error: ApiErrorBody.Error) : IdsStreamEvent()
}

@Serializable
internal data class IdsStreamGroup(
    @SerialName("keys") val keys: List<String>,
    @SerialName("total") val total: Long,
    @SerialName("ids") val ids: List<Uuid>,
)
