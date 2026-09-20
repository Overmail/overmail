package es.jvbabi.overmail.server.http.email.list

import es.jvbabi.overmail.server.database.models.EmailUsers
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.database.models.User
import kotlin.uuid.Uuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select

/** What one page may ask for. A windowed table asks for a screen and some overscan. */
internal const val MAX_LIMIT = 500
internal const val DEFAULT_LIMIT = 100

/**
 * As many ids as one stretch carries. A group of a listing is a day or a correspondent of mail,
 * so this is far above what anybody has in one -- it is here so a request cannot be made to
 * stream a mailbox, not to page. [EmailIdsResponse.total] says when it bit.
 */
internal const val MAX_IDS = 10_000

/**
 * The three questions a listing is read with: what shape it has, what sits on one page of it, and
 * what a whole stretch of it holds.
 *
 * Plain functions over an open transaction rather than route handlers, because there are two ways
 * in -- the endpoints beside this file, and the listing socket, which pushes the same answers
 * whenever the mailbox moves. Asking in one place is what keeps the counts a client lays its
 * headers out from and the ids it draws about the same mails; the filter is built once for the
 * same reason, see [MailFilter].
 *
 * Every one of them takes the filter already read, so a caller that answers twice for the same
 * listing reads the parameters once.
 */

/**
 * What the listing is cut into: one row per combination of the levels that holds mail at all,
 * with the keys outermost first.
 *
 * Counts, not ids -- the groups are the *shape* of the list, so a windowed table can lay out its
 * headers and size its scrollbar before a single mail is loaded. A mail is under exactly one of
 * them, so the counts add up to the listing.
 *
 * No groupings at all is one group over everything, as an ungrouped listing still has a shape.
 */
internal fun listingGroups(
    userId: User.Id,
    filter: MailFilter,
    groupings: List<MailGroupingKind>,
): EmailGroupsResponse {
    val keys: List<Expression<*>> = groupings.map { grouping -> grouping.groupKey() }

    val groups = if (keys.isEmpty()) {
        // One stretch over everything: a listing without headers, which is still a shape a
        // client can lay out.
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
        // Not `group by` on the expressions again: a key carries parameters -- the boundaries of
        // the date stretches, the actions of the archive log -- and a database reads the
        // placeholder in the group by as another one than the placeholder in the select, however
        // identical the text looks. Postgres says so outright, H2 too. Named once and grouped by
        // the name, both agree.
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

    return EmailGroupsResponse(
        groupings = groupings.map { grouping -> grouping.wire },
        groups = groups,
    )
}

/**
 * The mails of one group, a screen at a time.
 *
 * Ids and a length, nothing else. What a row shows is subscribed per mail over the content
 * socket, so this answer stays the same size whether a row carries a subject or a whole thread.
 *
 * A group and an offset into it, rather than a cursor through the whole listing: the groups are
 * the shape a client laid its rows out from, so a table that scrolls into the middle asks for
 * exactly the rows it is missing instead of paging its way down to them -- and an order that is
 * not the send time has no cursor to page by in the first place.
 */
internal fun listingPage(
    userId: User.Id,
    filter: MailFilter,
    group: Op<Boolean>,
    sorting: Pair<MailSorting, Boolean>,
    limit: Int,
    offset: Int,
): EmailListResponse {
    // The sender's address is joined in only for the sort that reads it; every other one is the
    // mails alone.
    val source = if (sorting.first.needsSender()) {
        Emails.leftJoin(ImapAccounts).innerJoin(EmailUsers)
    } else {
        Emails.leftJoin(ImapAccounts)
    }

    val mails = source
        .select(Emails.id)
        .where { (ImapAccounts.user eq userId) and filter.predicate() and group }

    // The count comes from the same query as the page, so the length a client checks its layout
    // against and the ids it draws cannot disagree.
    val total = mails.count()

    val natural = sorting.first.naturalOrder()
    val order = if (!sorting.second) natural
    else if (natural == SortOrder.DESC) SortOrder.ASC
    else SortOrder.DESC

    val page = mails
        .orderBy(sorting.first.orderBy() to order, Emails.id to order)
        .limit(limit)
        .offset(offset.toLong())
        .map { row -> row[Emails.id].value }

    return EmailListResponse(total = total, ids = page)
}

/**
 * Every mail of one group in one go -- which is what ticking a whole stretch of the table needs,
 * headers included: picking the group at the top of a day is picking the mails under it, and the
 * client only holds the ids of the rows it has drawn.
 */
internal fun listingIds(userId: User.Id, filter: MailFilter, group: Op<Boolean>): EmailIdsResponse {
    val stretch = Emails
        .leftJoin(ImapAccounts)
        .select(Emails.id)
        .where { (ImapAccounts.user eq userId) and filter.predicate() and group }

    // From the same query as the ids, so a client can tell an answer that was cut from one that
    // holds the whole group.
    val total = stretch.count()

    return EmailIdsResponse(
        total = total,
        // Newest first, like the listing's own default; what a selection does with them does not
        // depend on their order.
        ids = stretch
            .orderBy(Emails.sent to SortOrder.DESC, Emails.id to SortOrder.DESC)
            .limit(MAX_IDS)
            .map { row -> row[Emails.id].value },
    )
}

@Serializable
internal data class EmailGroupsResponse(
    /** The levels these groups are cut by, outermost first -- what `by` asked for. */
    @SerialName("groupings") val groupings: List<String>,
    @SerialName("groups") val groups: List<EmailGroup>,
)

@Serializable
internal data class EmailGroup(
    /**
     * One key per level, outermost first: a day as `yyyy-mm-dd`, an id for a sender or an
     * account, `true`/`false` for read, an archive state by name. Empty for the one stretch of an
     * ungrouped listing.
     */
    @SerialName("keys") val keys: List<String>,
    @SerialName("count") val count: Long,
)

@Serializable
internal data class EmailListResponse(
    /** How long the group is, not how much of it was asked for. */
    @SerialName("total") val total: Long,
    @SerialName("ids") val ids: List<Uuid>,
)

@Serializable
internal data class EmailIdsResponse(
    /** How many mails the stretch holds. More than [ids] when the answer was cut, see [MAX_IDS]. */
    @SerialName("total") val total: Long,
    @SerialName("ids") val ids: List<Uuid>,
)
