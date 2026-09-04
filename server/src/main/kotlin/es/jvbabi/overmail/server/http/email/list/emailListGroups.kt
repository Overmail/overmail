package es.jvbabi.overmail.server.http.email.list

import es.jvbabi.overmail.server.auth.user
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.database.models.emailIsNotSpam
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.datetime.Date
import org.jetbrains.exposed.v1.jdbc.select

/**
 * How the listing is cut into stretches: `GET /api/emails/list/groups?by=date`.
 *
 * Counts, not ids -- the groups are the *shape* of the list, so a windowed table can lay out its
 * headers and size its scrollbar before a single mail is loaded. The stretches come in the order
 * the listing itself has (newest first) and every mail is in exactly one of them, so the n-th
 * mail row of a layout built from these is the n-th mail of `GET /api/emails/list`.
 *
 * `by=date` is one stretch per calendar day. Folding those into what a reader is shown -- today,
 * yesterday, the rest of this week, the rest of this month, then month by month -- is the
 * client's, because it is a question of wording and of which day boundaries the reader lives in.
 *
 * Days are the server's days, like everywhere a date is grouped here (see `homeSocket`).
 */
fun Route.emailListGroups() {
    authenticate {
        get {
            val requested = call.request.queryParameters["by"] ?: EmailGrouping.NONE.wire
            val grouping = EmailGrouping.entries.firstOrNull { it.wire == requested }
                ?: return@get call.respond(HttpStatusCode.BadRequest)

            val database = call.application.dependencies.resolve<OvermailDatabase>()
            val userId = call.user.id.value

            val groups = database.query {
                val mails = Emails.id.count()

                when (grouping) {
                    // One stretch over everything: a listing without headers, which is still a
                    // shape a client can lay out.
                    EmailGrouping.NONE -> listOf(
                        EmailGroup(
                            key = null,
                            count = Emails
                                .leftJoin(ImapAccounts)
                                .select(Emails.id)
                                .where { (ImapAccounts.user eq userId) and emailIsNotSpam() }
                                .count(),
                        )
                    )

                    EmailGrouping.DATE -> {
                        // Suppressed, not outdated: kotlinx' `Instant` is a typealias of the one
                        // in `kotlin.time` now, which makes the deprecated overload and its
                        // replacement the same signature, and the call lands on the deprecated one.
                        @Suppress("DEPRECATION")
                        val day = Date(Emails.sent)

                        Emails
                            .leftJoin(ImapAccounts)
                            .select(day, mails)
                            .where { (ImapAccounts.user eq userId) and emailIsNotSpam() }
                            .groupBy(day)
                            .orderBy(day, SortOrder.DESC)
                            .map { row -> EmailGroup(key = row[day].toString(), count = row[mails]) }
                    }
                }
            }

            call.respond(EmailGroupsResponse(grouping = grouping.wire, groups = groups))
        }
    }
}

/** What a listing can be cut by. `none` is the whole mailbox as one stretch. */
private enum class EmailGrouping(val wire: String) {
    NONE("none"),
    DATE("date"),
}

@Serializable
private data class EmailGroupsResponse(
    @SerialName("grouping") val grouping: String,
    @SerialName("groups") val groups: List<EmailGroup>,
)

@Serializable
private data class EmailGroup(
    /** `yyyy-mm-dd` for a day, null for the one stretch of an ungrouped listing. */
    @SerialName("key") val key: String?,
    @SerialName("count") val count: Long,
)
