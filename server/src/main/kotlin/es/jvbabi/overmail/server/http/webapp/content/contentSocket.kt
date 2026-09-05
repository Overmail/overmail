package es.jvbabi.overmail.server.http.webapp.content

import es.jvbabi.overmail.server.data.notifier.MailEvent
import es.jvbabi.overmail.server.data.notifier.MailNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUser
import es.jvbabi.overmail.server.jobs.avatar.AvatarQueue
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.websocket.sendSerialized
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
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

/**
 * How long a change waits for the ones behind it. A classification run touches a mail several
 * times within a second, and an import cycle touches many mails in a row -- this turns each burst
 * into one message instead of one per write.
 */
private val CHANGE_DEBOUNCE = 150.milliseconds

/**
 * How long an announcement that the mail moved waits for the ones behind it.
 *
 * Longer than [CHANGE_DEBOUNCE], because it costs the other side more: a mail on screen is one
 * message, while a listing answers this by re-reading its length, its days and the page it is
 * looking at. An import cycle that runs for a minute does not have to be followed mail by mail.
 */
private val MOVED_DEBOUNCE = 1.seconds

/**
 * The most mails one socket keeps up to date.
 *
 * A screen has a window, not a mailbox: the stack holds ten, a table its visible rows and some
 * overscan. Ten times that is room for a client that lets go a little late, and a ceiling for one
 * that never lets go -- ids over it are answered as unavailable rather than left loading, because
 * a client at this number is a leak and a spinner would hide it.
 */
private const val MAX_SUBSCRIPTIONS = 2_000

private val json = Json { ignoreUnknownKeys = true }

/**
 * The mails a screen is showing, kept current: `GET /api/webapp/content/socket`.
 *
 * A client subscribes to the ids it has on screen and gets their metadata right away, then again
 * whenever one of them changes -- read, labelled, archived. It unsubscribes when nothing is
 * looking at a mail any more, and this socket forgets it.
 *
 * Every message carries the whole metadata of a mail, never a patch: a client merges what arrives
 * over what it had, an update it missed is corrected by the next one, and a reconnect starts with
 * fresh snapshots because the subscriptions are asked for again.
 *
 * Alongside them goes one announcement for the mail this user has as a whole -- see
 * [ContentServerMessage.Moved]: what a listing holds are positions, and a mail arriving or leaving
 * moves them all, including the ones of mails nobody has subscribed to.
 */
fun Route.contentSocket() {
    authenticate {
        webSocket {
            val database = application.dependencies.resolve<OvermailDatabase>()
            val mailNotifier = application.dependencies.resolve<MailNotifier>()
            val avatarQueue = application.dependencies.resolve<AvatarQueue>()
            val user = call.requireAuthenticatedUser()

            /** The ids this socket keeps up to date. Guarded by [lock]. */
            val subscribed = mutableSetOf<Uuid>()

            /**
             * Who sent the mails above, as they last went out. A picture is found for an address,
             * not for a mail, so this is how an address book event turns into the ids on screen
             * that it changed. Guarded by [lock].
             */
            val senderByEmail = mutableMapOf<Uuid, Uuid>()
            val lock = Mutex()

            /** Ids to look at again, filled by the subscription and drained in bursts below. */
            val changes = Channel<Uuid>(Channel.UNLIMITED)

            /**
             * That mail moved at all. Conflated: the announcement says nothing but that it
             * happened, so a burst of them is one token and one message.
             */
            val moved = Channel<Unit>(Channel.CONFLATED)

            suspend fun sendMeta(ids: Collection<Uuid>) {
                if (ids.isEmpty()) return

                val mails = database.query { loadEmailMeta(user.id.value, ids) }
                if (mails.isNotEmpty()) {
                    lock.withLock {
                        mails.forEach { mail -> senderByEmail[mail.id] = mail.sender.id }
                    }
                    sendSerialized<ContentServerMessage>(ContentServerMessage.Emails(mails))

                    // A lookup is a request to a third party, so it happens after the mails went
                    // out rather than inside their query. Whatever is found arrives as a change
                    // to the sender and comes back over this socket, see MailEvent.SenderChanged.
                    mails.map { it.sender }
                        .filter { sender -> sender.avatarUrl == null }
                        .map { sender -> sender.id }
                        .distinct()
                        .forEach(avatarQueue::enqueue)
                }

                // An id that answered nothing does not exist, or is not this user's. The client
                // is told so it can stop waiting; it is dropped here so a change to a mail
                // nobody can see is not looked up again and again.
                val unknown = ids.toSet() - mails.map { it.id }.toSet()
                if (unknown.isNotEmpty()) {
                    lock.withLock {
                        subscribed -= unknown
                        senderByEmail.keys -= unknown
                    }
                    sendSerialized<ContentServerMessage>(ContentServerMessage.Unknown(unknown.toList()))
                }
            }

            // The subscription is up before the first client message is read, so a change that
            // happens while a snapshot is being loaded is not lost: it either made it into that
            // snapshot or it is sitting in [changes] and the mail is read again.
            val listening = CompletableDeferred<Unit>()
            launch {
                mailNotifier.subscribe(user.id.value)
                    .onSubscription { listening.complete(Unit) }
                    .collect { event ->
                        when (event) {
                            is MailEvent.Changed -> {
                                changes.send(event.emailId)
                                // The mail itself goes out for every change; the announcement
                                // only for the ones a listing has to re-read for.
                                if (event.movedListings) moved.trySend(Unit)
                            }
                            // Translated to the mails on screen here rather than sent on as an
                            // address: everything past this point deals in mail ids.
                            is MailEvent.SenderChanged -> {
                                val fromSender = lock.withLock {
                                    senderByEmail.filterValues { it == event.senderId }.keys.toList()
                                }
                                fromSender.forEach { changes.send(it) }
                            }
                        }
                    }
            }

            launch {
                while (true) {
                    // Waits for the first change, then lets the burst behind it gather. Draining
                    // after the delay rather than restarting it keeps a busy mailbox from
                    // starving a client that is waiting for one of these mails.
                    val burst = mutableSetOf(changes.receive())
                    delay(CHANGE_DEBOUNCE)
                    while (true) burst += changes.tryReceive().getOrNull() ?: break

                    val wanted = lock.withLock { burst.filter { it in subscribed } }
                    sendMeta(wanted)
                }
            }

            // Its own loop, on its own clock: this one is not about the mails on screen, and the
            // listings behind them are read again for every announcement.
            launch {
                while (true) {
                    moved.receive()
                    delay(MOVED_DEBOUNCE)
                    // Whatever arrived during the wait is covered by the message about to go out.
                    moved.tryReceive()
                    sendSerialized<ContentServerMessage>(ContentServerMessage.Moved)
                }
            }

            listening.await()

            for (frame in incoming) {
                val text = (frame as? Frame.Text ?: continue).readText()
                when (val message = json.decodeFromString<ContentClientMessage>(text)) {
                    is ContentClientMessage.SubscribeEmails -> {
                        val ids = message.ids.distinct()
                        val room = lock.withLock {
                            val room = (MAX_SUBSCRIPTIONS - subscribed.size).coerceAtLeast(0)
                            subscribed += ids.take(room)
                            room
                        }

                        // Answered even for an id that was already subscribed: the client asks
                        // because it has nothing to show, and the snapshot is what it is after.
                        sendMeta(ids.take(room))

                        val rejected = ids.drop(room)
                        if (rejected.isNotEmpty()) {
                            sendSerialized<ContentServerMessage>(ContentServerMessage.Unknown(rejected))
                        }
                    }

                    is ContentClientMessage.UnsubscribeEmails -> {
                        lock.withLock {
                            subscribed -= message.ids.toSet()
                            senderByEmail.keys -= message.ids.toSet()
                        }
                    }
                }
            }
        }
    }
}

@Serializable
private sealed class ContentServerMessage {
    /** Snapshots, updates, both -- the client cannot tell and does not need to. */
    @Serializable
    @SerialName("data.emails")
    data class Emails(@SerialName("emails") val emails: List<EmailMeta>) : ContentServerMessage()

    /**
     * Nothing to show for these ids: gone, never existed, somebody else's, or more than this
     * socket serves. Told apart nowhere, so no client learns which mails exist.
     */
    @Serializable
    @SerialName("data.emails.unknown")
    data class Unknown(@SerialName("ids") val ids: List<Uuid>) : ContentServerMessage()

    /**
     * This user's mail moved: one arrived, was archived, unarchived or filed as spam. Carries
     * nothing -- what a listing is is a query, and this is only the word that its answer is old.
     *
     * Sent whatever is subscribed, which is the point: a mail that just arrived is at the top of
     * a listing and no client can have asked for it yet.
     */
    @Serializable
    @SerialName("update.mails.moved")
    data object Moved : ContentServerMessage()
}

@Serializable
private sealed class ContentClientMessage {
    @Serializable
    @SerialName("subscribe.emails")
    data class SubscribeEmails(@SerialName("ids") val ids: List<Uuid>) : ContentClientMessage()

    @Serializable
    @SerialName("unsubscribe.emails")
    data class UnsubscribeEmails(@SerialName("ids") val ids: List<Uuid>) : ContentClientMessage()
}
