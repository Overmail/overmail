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
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.datetime.Date
import org.jetbrains.exposed.v1.datetime.Year
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/**
 * One import cycle inserts mail after mail and announces each one, so what is on screen is read
 * once the burst has settled instead of once per mail.
 */
private val REFRESH_DEBOUNCE = 250.milliseconds

/**
 * What a client may ask for. Below that there is nothing to see -- no mail predates the epoch --
 * and the upper end only keeps a nonsense number out of the date arithmetic.
 */
private val SUPPORTED_YEARS = 1970..9999

private val json = Json { ignoreUnknownKeys = true }

/**
 * What the home screen keeps on screen: `GET /api/webapp/home/socket`.
 *
 * Two things travel over it, both re-read and re-sent whenever the mailbox moves -- a mail
 * arriving, being archived, unarchived or filed as spam:
 *
 * - the size of the mailbox, which is sent on connect,
 * - the mails per day of a year, for the heatmap. The current year is sent on connect as well;
 *   any other year is sent once the client asks for it and then stays subscribed for as long as
 *   this socket lives.
 *
 * Everything is re-read rather than adjusted by a delta: what is in the mailbox is a query over
 * an event log (see `emailIsNotArchived`), and a client that missed one message would otherwise
 * stay wrong until it reconnects. Only what actually changed goes out.
 */
fun Route.homeSocket() {
    authenticate {
        webSocket {
            val database = application.dependencies.resolve<OvermailDatabase>()
            val mailboxNotifier = application.dependencies.resolve<MailboxNotifier>()
            val user = call.user

            // Two coroutines write here: the loop below, when a year is asked for, and the
            // subscription, when something moved. The lock keeps one from sending a year the
            // other is in the middle of reading.
            val sending = Mutex()

            var sentCount: Long? = null

            /** The years the client is looking at, with what it last got for each. */
            val sentGraphs = mutableMapOf<Int, HomeServerMessage.MailGraph>()

            suspend fun countMailbox(): Long = database.query {
                Emails
                    .leftJoin(ImapAccounts)
                    .select(Emails.id)
                    .where { (ImapAccounts.user eq user.id) and emailIsNotArchived() }
                    .count()
            }

            /**
             * How much mail arrived on every day of [year], and every year there is mail in.
             *
             * Counts what arrived, archived or not: the heatmap is a record of how busy a day
             * was, and cleaning up afterwards does not make a day quieter in hindsight.
             *
             * Grouped in the database rather than over loaded mails -- a year of a busy mailbox
             * is tens of thousands of rows and all that is wanted is one number per day. Days
             * are UTC days, so the year the socket picks is a UTC year too.
             */
            suspend fun mailGraph(year: Int): HomeServerMessage.MailGraph = database.query {
                val from = LocalDate(year, 1, 1).atStartOfDayIn(TimeZone.UTC)
                val until = LocalDate(year + 1, 1, 1).atStartOfDayIn(TimeZone.UTC)
                // Suppressed, not outdated: kotlinx' `Instant` is a typealias of the one in
                // `kotlin.time` now, which makes the deprecated overload and its replacement the
                // same signature, and the call lands on the deprecated one.
                @Suppress("DEPRECATION")
                val day = Date(Emails.sent)
                val mails = Emails.id.count()

                // The range is on the column itself and not on the day of it, so the index over
                // (account, sent) still carries the query.
                val days = (Emails innerJoin ImapAccounts)
                    .select(day, mails)
                    .where {
                        (ImapAccounts.user eq user.id) and
                                (Emails.sent greaterEq from) and
                                (Emails.sent less until)
                    }
                    .groupBy(day)
                    .associate { row -> row[day].toString() to row[mails].toInt() }

                @Suppress("DEPRECATION")
                val mailYear = Year(Emails.sent)
                val availableYears = (Emails innerJoin ImapAccounts)
                    .select(mailYear)
                    .where { ImapAccounts.user eq user.id }
                    .groupBy(mailYear)
                    // Only so the answer reads as a calendar does; nothing depends on the order.
                    .orderBy(mailYear, SortOrder.ASC)
                    .map { row -> row[mailYear] }

                HomeServerMessage.MailGraph(year = year, availableYears = availableYears, days = days)
            }

            suspend fun sendMailboxCount() {
                val count = countMailbox()
                // Unchanged happens: a mail that was already archived, or one that arrives while
                // another leaves.
                if (count == sentCount) return
                sentCount = count
                sendSerialized<HomeServerMessage>(HomeServerMessage.MailboxCount(count))
            }

            /** Also what a first request for a year runs through: nothing was sent for it yet. */
            suspend fun sendMailGraph(year: Int) {
                val graph = mailGraph(year)
                if (graph == sentGraphs[year]) return
                sentGraphs[year] = graph
                sendSerialized<HomeServerMessage>(graph)
            }

            // The first read happens from onSubscription, so it cannot fall into the gap
            // between reading and listening: an event fired in there would reach nobody, and the
            // client would sit on a stale number until it reconnects. Subscribed first, the
            // event is buffered and the collector below reads again.
            launch {
                mailboxNotifier.subscribe(user.id.value)
                    .onSubscription {
                        sending.withLock {
                            sendMailboxCount()
                            sendMailGraph(Clock.System.now().toLocalDateTime(TimeZone.UTC).year)
                        }
                    }
                    .collectLatest {
                        delay(REFRESH_DEBOUNCE)
                        sending.withLock {
                            sendMailboxCount()
                            // Only the years somebody is looking at; the rest are nobody's
                            // business until they are asked for.
                            sentGraphs.keys.toList().forEach { year -> sendMailGraph(year) }
                        }
                    }
            }

            for (frame in incoming) {
                val text = (frame as? Frame.Text ?: continue).readText()
                when (val message = json.decodeFromString<HomeClientMessage>(text)) {
                    is HomeClientMessage.RequestMailGraph -> {
                        // A year outside the calendar is not answered and not remembered.
                        if (message.year !in SUPPORTED_YEARS) continue
                        sending.withLock { sendMailGraph(message.year) }
                    }
                }
            }
        }
    }
}

@Serializable
private sealed class HomeServerMessage {
    /** Mails in the mailbox: everything that is not archived and not spam. */
    @Serializable
    @SerialName("data.mailbox.count")
    data class MailboxCount(@SerialName("unarchived") val unarchived: Long) : HomeServerMessage()

    /** One year of the heatmap. */
    @Serializable
    @SerialName("data.mail_graph")
    data class MailGraph(
        @SerialName("year") val year: Int,
        /** Every year there is mail in, oldest first. [year] need not be one of them. */
        @SerialName("available_years") val availableYears: List<Int>,
        /** `yyyy-mm-dd` to the mails that arrived that day; a day without mail is not in here. */
        @SerialName("days") val days: Map<String, Int>,
    ) : HomeServerMessage()
}

@Serializable
private sealed class HomeClientMessage {
    /** Asks for a year of the heatmap, and to be kept up to date on it. */
    @Serializable
    @SerialName("request.mail_graph")
    data class RequestMailGraph(@SerialName("year") val year: Int) : HomeClientMessage()
}
