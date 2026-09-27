package es.jvbabi.overmail.server.http.email.changes

import es.jvbabi.overmail.server.data.notifier.MailEvent
import es.jvbabi.overmail.server.data.notifier.MailNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.EmailAvatars
import es.jvbabi.overmail.server.database.models.EmailUsers
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.dependency
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import es.jvbabi.overmail.server.http.avatar.avatarPadding
import es.jvbabi.overmail.server.http.avatar.avatarUrlOrNull
import es.jvbabi.overmail.server.http.webapp.content.EmailMeta
import es.jvbabi.overmail.server.http.webapp.content.loadEmailMeta
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.Route
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select

private val json = Json { encodeDefaults = true }

/**
 * How long a change waits for the ones behind it. A classification run touches a mail several
 * times within a second, and an import cycle touches many mails in a row -- this turns each burst
 * into one event instead of one per write.
 */
private val CHANGE_DEBOUNCE = 150.milliseconds

/**
 * How long a quiet stream waits before it writes a comment. Only a write finds out that the client
 * went away -- without it, a stream nobody reads any more would sit waiting for the next change.
 */
private val KEEP_ALIVE = 15.seconds

/**
 * Everything that changes about this user's mail, as it happens: `GET /api/emails/changes`.
 *
 * For a client that keeps a database of its own: it loads what it wants through
 * `QUERY /api/emails/meta` and applies these on top, skipping the ones about mails it does not
 * hold. Unlike the content socket nothing is subscribed -- this server cannot know what a client
 * holds, so every change goes out, as the whole [EmailMeta] rather than a patch. An event a client
 * missed is corrected by the next one about the same mail.
 *
 * What it cannot correct is an event missed while no stream was open: a client loads what it
 * holds again whenever it (re)connects, once `ready` says this stream is listening.
 */
fun Route.emailChanges(keepAlive: Duration = KEEP_ALIVE) {
    authenticate {
        /**
         * Follow every change to the current user's mail.
         *
         * Description: Server-sent events. `ready` once the stream listens, which is when to load what you hold; then `emails` with the whole metadata of mails that changed, `removed` with the ids of mails that are gone and `senders` with correspondents whose picture changed.
         *
         * Tag: Emails
         *
         * Responses:
         *   - 200 text/event-stream [ChangeEvent] The events
         */
        sse {
            follow(
                userId = call.requireAuthenticatedUserId(),
                database = call.database(),
                mailNotifier = call.dependency<MailNotifier>(),
                keepAlive = keepAlive,
            )
        }
    }
}

/** What to look at again: a mail, or a correspondent. */
private sealed interface Change {
    data class Email(val id: Uuid) : Change
    data class Sender(val id: Uuid) : Change
}

/** Sends every change until the client leaves. */
private suspend fun ServerSSESession.follow(
    userId: User.Id,
    database: OvermailDatabase,
    mailNotifier: MailNotifier,
    keepAlive: Duration,
) {
    val changes = Channel<Change>(Channel.UNLIMITED)

    // Up before `ready` goes out, so a change that happens while the client is loading what it
    // holds is not lost: it either made it into that load or it is sent here.
    val listening = CompletableDeferred<Unit>()
    launch {
        mailNotifier.subscribe(userId)
            .onSubscription { listening.complete(Unit) }
            .collect { event ->
                when (event) {
                    // Whatever it was, and whether it moved anything or not: a client with its
                    // own copy of the mail needs the flag as much as the move.
                    is MailEvent.Changed -> changes.send(Change.Email(event.emailId))
                    is MailEvent.SenderChanged -> changes.send(Change.Sender(event.senderId))
                }
            }
    }
    listening.await()
    send(ChangeEvent.Ready)

    while (true) {
        // Waits for the first change, then lets the burst behind it gather. Draining after the
        // delay rather than restarting it keeps a busy mailbox from starving the client.
        val first = withTimeoutOrNull(keepAlive) { changes.receive() }
        if (first == null) {
            send(ServerSentEvent(comments = "keep-alive"))
            continue
        }

        val burst = mutableSetOf(first)
        delay(CHANGE_DEBOUNCE)
        while (true) burst += changes.tryReceive().getOrNull() ?: break

        val emailIds = burst.filterIsInstance<Change.Email>().map { it.id }
        val senderIds = burst.filterIsInstance<Change.Sender>().map { it.id }

        val (emails, senders) = database.query {
            loadEmailMeta(userId, emailIds) to loadSenders(userId, senderIds)
        }

        if (emails.isNotEmpty()) send(ChangeEvent.Emails(emails))

        // What changed and did not come back is not there any more -- or never was this user's,
        // which the notifier, keyed by user, does not announce.
        val removed = emailIds.toSet() - emails.map { it.id }.toSet()
        if (removed.isNotEmpty()) send(ChangeEvent.Removed(removed.toList()))

        if (senders.isNotEmpty()) send(ChangeEvent.Senders(senders))
    }
}

/** The correspondents among [ids] that are [userId]'s, as far as a picture is concerned. */
private fun loadSenders(userId: User.Id, ids: List<Uuid>): List<ChangeEvent.Sender> {
    if (ids.isEmpty()) return emptyList()

    return EmailUsers
        .leftJoin(EmailAvatars)
        .select(EmailUsers.id, EmailUsers.address, EmailUsers.avatar, EmailAvatars.circlePadding)
        .where { (EmailUsers.id inList ids) and (EmailUsers.user eq userId) }
        .map { row ->
            ChangeEvent.Sender(
                id = row[EmailUsers.id].value,
                address = row[EmailUsers.address],
                avatarUrl = row.avatarUrlOrNull(),
                avatarPadding = row.avatarPadding(),
            )
        }
}

private suspend fun ServerSSESession.send(event: ChangeEvent) =
    send(ServerSentEvent(data = json.encodeToString<ChangeEvent>(event)))

@Serializable
internal sealed class ChangeEvent {

    /** The stream is listening: what the client loads from here on cannot miss a change. */
    @Serializable
    @SerialName("ready")
    data object Ready : ChangeEvent()

    /** The whole metadata of mails that changed, replacing what the client has of them. */
    @Serializable
    @SerialName("emails")
    data class Emails(@SerialName("emails") val emails: List<EmailMeta>) : ChangeEvent()

    /** Mails that are gone. */
    @Serializable
    @SerialName("removed")
    data class Removed(@SerialName("ids") val ids: List<Uuid>) : ChangeEvent()

    /** Correspondents whose picture changed; every mail from or to them shows the new one. */
    @Serializable
    @SerialName("senders")
    data class Senders(@SerialName("senders") val senders: List<Sender>) : ChangeEvent()

    @Serializable
    data class Sender(
        @SerialName("id") val id: Uuid,
        @SerialName("address") val address: String,
        @SerialName("avatar_url") val avatarUrl: String?,
        @SerialName("avatar_padding") val avatarPadding: Double?,
    )
}
