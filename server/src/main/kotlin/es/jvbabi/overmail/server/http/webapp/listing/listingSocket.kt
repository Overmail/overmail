package es.jvbabi.overmail.server.http.webapp.listing

import es.jvbabi.overmail.server.data.notifier.MailEvent
import es.jvbabi.overmail.server.data.notifier.MailNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.http.api.ApiErrorBody
import es.jvbabi.overmail.server.http.api.ApiException
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUser
import es.jvbabi.overmail.server.http.clientWebSocket
import es.jvbabi.overmail.server.http.email.list.DEFAULT_LIMIT
import es.jvbabi.overmail.server.http.email.list.MAX_LIMIT
import es.jvbabi.overmail.server.http.email.list.MailFilter
import es.jvbabi.overmail.server.http.email.list.MailGroupingKind
import es.jvbabi.overmail.server.http.email.list.MailSorting
import es.jvbabi.overmail.server.http.email.list.listingGroups
import es.jvbabi.overmail.server.http.email.list.listingPage
import es.jvbabi.overmail.server.http.email.list.mailFilter
import es.jvbabi.overmail.server.http.email.list.mailGroup
import es.jvbabi.overmail.server.http.email.list.mailGroupings
import es.jvbabi.overmail.server.http.email.list.mailSorting
import io.ktor.http.Parameters
import io.ktor.http.parseQueryString
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.websocket.sendSerialized
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Op

/**
 * How long a move waits for the ones behind it.
 *
 * The same reasoning as the content socket's own announcement, and the same number: answering
 * costs a count over the mailbox and a page query per window, so an import cycle that runs for a
 * minute must not be followed mail by mail.
 */
private val MOVED_DEBOUNCE = 1000.milliseconds

/**
 * The most pages one socket keeps up to date.
 *
 * A window is a screen and some overscan, which spans a handful of groups at most -- this is room
 * for a client that asks generously and a ceiling for one that never lets go.
 */
private const val MAX_PAGES = 16

private val json = Json { ignoreUnknownKeys = true }

/**
 * The listing a screen is showing, kept current: `GET /api/webapp/listing/socket`.
 *
 * A client says which listing it is on and which pages of it it is looking at; this answers with
 * the shape and those pages, and answers again -- unasked -- whenever this user's mail moves.
 * What used to take a round trip (the content socket said that something moved, the client then
 * fetched the same two queries over http) is one hop.
 *
 * Only the index travels here: which groups there are, how long each is, and which mail sits at
 * which offset. What a row *shows* is subscribed per mail over the content socket, exactly as
 * before -- a mail is what it is whatever listing it turns up in.
 *
 * The listing is named by the query string the endpoints take, verbatim ([WatchListing.query]),
 * and read with the very same functions. A client builds one string for both ways in, and there
 * is no second spelling of a filter to drift from the first.
 *
 * Everything is re-read and re-sent rather than patched: what a listing holds is a query, and a
 * client that missed a message would otherwise stay wrong until it reconnects. Only what actually
 * changed goes out, see [sent].
 */
fun Route.listingSocket() {
    authenticate {
        clientWebSocket {
            val database = application.dependencies.resolve<OvermailDatabase>()
            val mailNotifier = application.dependencies.resolve<MailNotifier>()
            val user = call.requireAuthenticatedUser()

            val lock = Mutex()

            /** What the client is on, or null until it said. Guarded by [lock]. */
            var listing: WatchedListing? = null

            /** The pages it is looking at, in the order it asked for them. Guarded by [lock]. */
            var pages: List<WatchedPage> = emptyList()

            /**
             * What went out last, by the key of the answer. An event says that mail moved, not
             * that what this socket shows of it is different -- a mail read in another tab moves
             * nothing, and a mail archived far below the window changes no page the client holds.
             *
             * Cleared whenever the listing changes: what was sent then was about another one.
             * Guarded by [lock].
             */
            val sent = mutableMapOf<String, ListingServerMessage>()

            /** That mail moved. Conflated: a burst of them is one token and one answer. */
            val moved = Channel<Unit>(Channel.CONFLATED)

            /**
             * Sends [message] unless exactly it went out last. [key] is what two answers about
             * the same thing share -- the shape, or one page of one group.
             */
            suspend fun send(key: String, message: ListingServerMessage) {
                val unchanged = lock.withLock {
                    if (sent[key] == message) true
                    else {
                        sent[key] = message
                        false
                    }
                }
                if (!unchanged) sendSerialized<ListingServerMessage>(message)
            }

            /** The shape of the listing, and the pages the client is looking at. */
            suspend fun answer(shape: Boolean, wanted: List<WatchedPage>) {
                val current = lock.withLock { listing } ?: return

                if (shape) {
                    val groups = database.query {
                        listingGroups(user.id.value, current.filter, current.groupings)
                    }
                    send(
                        "groups",
                        ListingServerMessage.Groups(
                            groupings = groups.groupings,
                            groups = groups.groups.map { group -> ListingGroup(group.keys, group.count) },
                        ),
                    )
                }

                for (page in wanted) {
                    val answer = database.query {
                        listingPage(
                            userId = user.id.value,
                            filter = current.filter,
                            group = page.predicate,
                            sorting = current.sorting,
                            limit = page.limit,
                            offset = page.offset,
                        )
                    }

                    send(
                        "page:" + page.group + ":" + page.offset,
                        ListingServerMessage.Page(
                            group = page.group,
                            offset = page.offset,
                            total = answer.total,
                            ids = answer.ids.map { id -> id.toString() },
                        ),
                    )
                }
            }

            // The subscription is up before the first client message is read, so a move that
            // happens while an answer is being loaded is not lost: it either made it into that
            // answer or it is sitting in [moved] and the listing is read again.
            val listening = CompletableDeferred<Unit>()
            launch {
                mailNotifier.subscribe(user.id.value)
                    .onSubscription { listening.complete(Unit) }
                    .collect { event ->
                        // Only a move concerns an index. A flag on the read state or a label
                        // leaves every listing holding the same mails in the same order, and the
                        // mail itself travels over the content socket.
                        if (event is MailEvent.Changed && event.movedListings) moved.trySend(Unit)
                    }
            }

            launch {
                while (true) {
                    moved.receive()
                    delay(MOVED_DEBOUNCE)
                    // Whatever arrived during the wait is covered by the answer about to go out.
                    moved.tryReceive()

                    val wanted = lock.withLock { pages }
                    answer(shape = true, wanted = wanted)
                }
            }

            listening.await()

            for (frame in incoming) {
                val text = (frame as? Frame.Text ?: continue).readText()

                try {
                    when (val message = json.decodeFromString<ListingClientMessage>(text)) {
                        is ListingClientMessage.WatchListing -> {
                            val watched = WatchedListing.of(message.query)

                            val wanted = lock.withLock {
                                listing = watched
                                // The pages were offsets into another listing; what the client
                                // is looking at now is whatever it asks for next.
                                pages = emptyList()
                                sent.clear()
                                pages
                            }

                            answer(shape = true, wanted = wanted)
                        }

                        is ListingClientMessage.WatchPages -> {
                            val watched = lock.withLock { listing }
                                ?: continue // nothing said what these offsets are into

                            val wanted = message.pages.take(MAX_PAGES).map { page ->
                                WatchedPage.of(watched, page)
                            }

                            lock.withLock {
                                pages = wanted
                                // A page nobody is looking at any more is not worth remembering,
                                // and it is what would otherwise grow for as long as somebody
                                // scrolls.
                                val keys = wanted.map { page -> "page:" + page.group + ":" + page.offset }
                                sent.keys.retainAll { key -> !key.startsWith("page:") || key in keys }
                            }

                            answer(shape = false, wanted = wanted)
                        }
                    }
                } catch (e: ApiException) {
                    // The same body the endpoints answer with, so a client reads one shape of
                    // error whichever way it asked. The connection stays up: what was wrong is
                    // the message, not the socket.
                    sendSerialized<ListingServerMessage>(ListingServerMessage.Failed(e.toBody().error))
                }
            }
        }
    }
}

/** The listing a client named, read once so every answer about it is cut the same way. */
private class WatchedListing(
    val filter: MailFilter,
    val groupings: List<MailGroupingKind>,
    val sorting: Pair<MailSorting, Boolean>,
    /** Kept to build the predicate of a group the client names later. */
    val parameters: Parameters,
) {
    companion object {
        /** Reads [query] the way the endpoints read a query string, or throws [ApiException]. */
        fun of(query: String): WatchedListing {
            val parameters = parseQueryString(query)

            return WatchedListing(
                filter = mailFilter(parameters),
                groupings = mailGroupings(parameters),
                sorting = mailSorting(parameters),
                parameters = parameters,
            )
        }
    }
}

/** One page of one group, as the client asked for it. */
private class WatchedPage(
    /** The keys of the group, comma joined -- what `group=` carries. Empty is the whole listing. */
    val group: String,
    val offset: Int,
    val limit: Int,
    val predicate: Op<Boolean>,
) {
    companion object {
        fun of(listing: WatchedListing, page: PageRequest): WatchedPage {
            // Through the same reader the endpoint uses, so a key that means nothing at this
            // level is the same 400 here as there.
            val parameters = Parameters.build {
                appendAll(listing.parameters)
                remove("group")
                if (page.group.isNotEmpty()) append("group", page.group)
            }

            return WatchedPage(
                group = page.group,
                offset = page.offset.coerceAtLeast(0),
                limit = (page.limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT),
                predicate = mailGroup(parameters, listing.groupings),
            )
        }
    }
}

@Serializable
private sealed class ListingClientMessage {
    /**
     * Which listing this socket is about, as the query string the endpoints take: the filter, the
     * levels and the sort. Says nothing about which part of it the client is looking at -- that
     * is [WatchPages], and it arrives once the table knows its window.
     */
    @Serializable
    @SerialName("watch.listing")
    data class WatchListing(@SerialName("query") val query: String) : ListingClientMessage()

    /**
     * The pages the client is looking at, which is what it wants kept up to date. The whole set
     * every time rather than one on and one off: it is a window, and what left it is as much part
     * of the message as what entered it.
     */
    @Serializable
    @SerialName("watch.pages")
    data class WatchPages(@SerialName("pages") val pages: List<PageRequest>) : ListingClientMessage()
}

@Serializable
private data class PageRequest(
    /** The keys of the group, comma joined. Empty is the listing as one group. */
    @SerialName("group") val group: String = "",
    @SerialName("offset") val offset: Int = 0,
    @SerialName("limit") val limit: Int? = null,
)

@Serializable
private sealed class ListingServerMessage {
    /**
     * What the listing is cut into. The shape, so a windowed table can lay out its headers and
     * size its scrollbar before a single mail is here.
     */
    @Serializable
    @SerialName("data.listing.groups")
    data class Groups(
        @SerialName("groupings") val groupings: List<String>,
        @SerialName("groups") val groups: List<ListingGroup>,
    ) : ListingServerMessage()

    /** Which mail sits at which offset of one group. */
    @Serializable
    @SerialName("data.listing.page")
    data class Page(
        @SerialName("group") val group: String,
        @SerialName("offset") val offset: Int,
        /** How long the group is, not how much of it this carries. */
        @SerialName("total") val total: Long,
        @SerialName("ids") val ids: List<String>,
    ) : ListingServerMessage()

    /**
     * The message could not be read: a filter that names something that is not an id, a group key
     * that means nothing at its level. The same body the endpoints answer with.
     */
    @Serializable
    @SerialName("data.listing.failed")
    data class Failed(@SerialName("error") val error: ApiErrorBody.Error) : ListingServerMessage()
}

@Serializable
private data class ListingGroup(
    @SerialName("keys") val keys: List<String>,
    @SerialName("count") val count: Long,
)
