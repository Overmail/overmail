package es.jvbabi.overmail.server.http.webapp.home

import es.jvbabi.overmail.server.auth.user
import es.jvbabi.overmail.server.data.notifier.MailboxNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.database.models.emailIsNotArchived
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.websocket.sendSerialized
import io.ktor.server.websocket.webSocket
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.time.Duration.Companion.milliseconds

/**
 * One import cycle inserts mail after mail and announces each one, so the count is read once the
 * burst has settled instead of once per mail.
 */
private val RECOUNT_DEBOUNCE = 250.milliseconds

/**
 * What the home screen keeps on screen: `GET /api/webapp/home/socket`.
 *
 * Sends the size of the mailbox right away and again whenever it moves -- a mail arriving, being
 * archived, unarchived or filed as spam. The number is re-read for every update rather than
 * adjusted by a delta: what counts as being in the mailbox is a query over an event log (see
 * `emailIsNotArchived`), and a client that missed a message would otherwise stay wrong until it
 * reconnects.
 */
fun Route.homeSocket() {
    authenticate {
        webSocket {
            val database = application.dependencies.resolve<OvermailDatabase>()
            val mailboxNotifier = application.dependencies.resolve<MailboxNotifier>()
            val user = call.user

            suspend fun countMailbox(): Long = database.query {
                Emails
                    .leftJoin(ImapAccounts)
                    .select(Emails.id)
                    .where { (ImapAccounts.user eq user.id) and emailIsNotArchived() }
                    .count()
            }

            var sentCount = countMailbox()
            sendSerialized<HomeServerMessage>(HomeServerMessage.MailboxCount(sentCount))

            // Subscribed after the first count, which is the order that cannot lose an update:
            // an event between the two is a count that is read again, not one that is missed.
            launch {
                mailboxNotifier.subscribe(user.id.value).collectLatest {
                    delay(RECOUNT_DEBOUNCE)
                    val count = countMailbox()
                    // Unchanged happens: a mail that was already archived, or one that arrives
                    // while another leaves.
                    if (count == sentCount) return@collectLatest
                    sentCount = count
                    sendSerialized<HomeServerMessage>(HomeServerMessage.MailboxCount(count))
                }
            }

            // The socket takes no messages; this is what keeps the handler suspended until the
            // client closes it, which cancels the subscription above with it.
            for (frame in incoming) Unit
        }
    }
}

@Serializable
private sealed class HomeServerMessage {
    /** Mails in the mailbox: everything that is not archived and not spam. */
    @Serializable
    @SerialName("data.mailbox.count")
    data class MailboxCount(@SerialName("unarchived") val unarchived: Long) : HomeServerMessage()
}
