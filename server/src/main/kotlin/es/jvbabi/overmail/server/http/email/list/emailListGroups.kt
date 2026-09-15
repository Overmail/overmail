package es.jvbabi.overmail.server.http.email.list

import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select

/**
 * What the listing is cut into: `GET /api/emails/list/groups?by=date_smart,sender`.
 *
 * Counts, not ids -- the groups are the *shape* of the list, so a windowed table can lay out its
 * headers and size its scrollbar before a single mail is loaded. One row per combination of the
 * levels that holds mail at all, with the keys outermost first; a mail is under exactly one of
 * them, so the counts add up to the listing.
 *
 * Which mails it counts is the filter, the same one the listing itself is drawn with, see
 * [MailFilter]. A stretch that counted mails the rows do not show would be a header over the
 * wrong number.
 *
 * The rows come in no particular order and carry no labels: what a group is called and where it
 * goes is the client's, which is also the only side that can fold days into "today" or put
 * senders in the order of the names it resolved. See [MailGroupingKind].
 *
 * No `by=` at all is one group over everything, as an ungrouped listing still has a shape.
 */
fun Route.emailListGroups() {
    authenticate {
        get {
            val groupings = call.mailGroupings()
            val filter = call.mailFilter()
            val userId = call.requireAuthenticatedUserId()

            val groups = call.database().query {
                val mails = Emails.id.count()
                val keys: List<Expression<*>> = groupings.map { grouping -> grouping.groupKey() }

                if (keys.isEmpty()) {
                    // One stretch over everything: a listing without headers, which is still a
                    // shape a client can lay out.
                    listOf(
                        EmailGroup(
                            keys = emptyList(),
                            count = Emails
                                .leftJoin(ImapAccounts)
                                .select(Emails.id)
                                .where { (ImapAccounts.user eq userId) and filter.predicate() }
                                .count(),
                        )
                    )
                } else {
                    // The keys are worked out in a subquery and counted outside it.
                    //
                    // Not `group by` on the expressions again: a key carries parameters -- the
                    // boundaries of the date stretches, the actions of the archive log -- and a
                    // database reads the placeholder in the group by as another one than the
                    // placeholder in the select, however identical the text looks. Postgres says
                    // so outright, H2 too. Named once and grouped by the name, both agree.
                    val named = keys.mapIndexed { index, key -> key.alias("group_key_$index") }

                    val mailsOf = Emails
                        .leftJoin(ImapAccounts)
                        .select(named + Emails.id)
                        .where { (ImapAccounts.user eq userId) and filter.predicate() }
                        .alias("grouped_mails")

                    val counted = mailsOf[Emails.id].count()
                    val columns = named.map { name -> mailsOf[name] }

                    mailsOf
                        .select(columns + counted)
                        .groupBy(*columns.toTypedArray())
                        .map { row ->
                            EmailGroup(
                                keys = columns.map { column -> row[column].toString() },
                                count = row[counted],
                            )
                        }
                }
            }

            call.respond(
                EmailGroupsResponse(
                    groupings = groupings.map { grouping -> grouping.wire },
                    groups = groups,
                )
            )
        }
    }
}

@Serializable
private data class EmailGroupsResponse(
    /** The levels these groups are cut by, outermost first -- what `by=` asked for. */
    @SerialName("groupings") val groupings: List<String>,
    @SerialName("groups") val groups: List<EmailGroup>,
)

@Serializable
private data class EmailGroup(
    /**
     * One key per level, outermost first: a day as `yyyy-mm-dd`, an id for a sender or an
     * account, `true`/`false` for read, an archive state by name. Empty for the one stretch of an
     * ungrouped listing.
     */
    @SerialName("keys") val keys: List<String>,
    @SerialName("count") val count: Long,
)

