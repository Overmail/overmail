package es.jvbabi.overmail.server.http.email.list

import es.jvbabi.overmail.server.database.models.EmailArchiveAction
import es.jvbabi.overmail.server.database.models.EmailLabels
import es.jvbabi.overmail.server.database.models.EmailRecipients
import es.jvbabi.overmail.server.database.models.EmailUsers
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.database.models.emailArchiveStateIs
import es.jvbabi.overmail.server.http.api.invalidRequest
import io.ktor.http.Parameters
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * The one value in a correspondent filter that is not an id: the addresses this account sends
 * from.
 *
 * Which addresses those are is the imap accounts' logins -- see [ImapAccounts.username], the same
 * list `GET /users/me` hands out as `addresses`. Written as a name rather than resolved by the
 * client into the ids of the moment: an address book entry for one's own address appears when the
 * first mail of it is imported, and a view stored last week would be filtering on a list from
 * then.
 *
 * With `imap_account_ids` set it means those accounts alone, which is what makes "sent" from one
 * mailbox a filter somebody can build out of two chips.
 */
const val SELF_ADDRESSES = "self"

/**
 * Who a mail is from or to, as a filter names them: address book entries by id, and -- through
 * [SELF_ADDRESSES] -- this account's own addresses.
 *
 * A set with nothing in it at all lets nothing through, like every other attribute of a filter.
 */
data class Correspondents(val ids: Set<Uuid>, val self: Boolean)

/**
 * What a listing leaves out: a view's filter, as a url says it.
 *
 * One parameter per attribute, named the way `ViewSettings.Filter` names them on the wire, and
 * with the same reading: absent restricts nothing, and a value that is there is the set of values
 * that pass -- so `archived_state=` with nothing behind it lets nothing through. Every attribute
 * that is set narrows the listing further; they are read together, not as alternatives.
 *
 * The three listing endpoints take the same filter, because the counts a client lays its headers
 * out from, the ids it draws and the stretch it picks have to be about the same mails. One place
 * to build the predicate is what keeps them from drifting apart.
 */
data class MailFilter(
    /** Where the mail sits. Null is every state, spam included -- that is what "no filter" says. */
    val archivedState: Set<EmailArchiveAction>?,
    /** True only read, false only unread, null both. */
    val readState: Boolean?,
    val imapAccountIds: Set<Uuid>?,
    /** Mails from any of them; see [SELF_ADDRESSES] for the one that is not a correspondent. */
    val sentBy: Correspondents?,
    /** Mails addressed to any of them, in any of the header fields -- To, Cc and Bcc alike. */
    val sentTo: Correspondents?,
    /** Mails carrying any of them, not all of them: a filter narrows a list, it does not build one. */
    val hasLabels: Set<Uuid>?,
) {
    /**
     * The predicate behind the filter. Correlates on [Emails], so it goes into the `where` of a
     * query over that table -- next to the one that says whose mails these are.
     */
    fun predicate(): Op<Boolean> {
        var predicate: Op<Boolean> = Op.TRUE

        if (archivedState != null) {
            // An or over the states, because a mail is in exactly one of them; a set with nothing
            // in it has nothing to be in, which is the "lets nothing through" above.
            var states: Op<Boolean> = Op.FALSE
            for (state in archivedState) states = states or emailArchiveStateIs(state)
            predicate = predicate and states
        }

        if (readState != null) predicate = predicate and (Emails.isRead eq readState)

        if (imapAccountIds != null) predicate = predicate and (Emails.imapAccount inList imapAccountIds)

        if (sentBy != null) {
            var from: Op<Boolean> = Op.FALSE
            if (sentBy.ids.isNotEmpty()) from = from or (Emails.sender inList sentBy.ids)
            if (sentBy.self) from = from or (Emails.sender inSubQuery ownCorrespondents(imapAccountIds))

            predicate = predicate and from
        }

        // Exists rather than a join: a mail addressed to two of them is still one mail, and a
        // join would put it in the listing twice.
        if (sentTo != null) {
            var to: Op<Boolean> = Op.FALSE
            if (sentTo.ids.isNotEmpty()) {
                to = to or exists(
                    EmailRecipients.selectAll().where {
                        (EmailRecipients.email eq Emails.id) and (EmailRecipients.emailUser inList sentTo.ids)
                    }
                )
            }
            if (sentTo.self) {
                to = to or exists(
                    EmailRecipients.selectAll().where {
                        (EmailRecipients.email eq Emails.id) and
                                (EmailRecipients.emailUser inSubQuery ownCorrespondents(imapAccountIds))
                    }
                )
            }

            predicate = predicate and to
        }

        if (hasLabels != null) {
            predicate = predicate and exists(
                EmailLabels.selectAll().where {
                    (EmailLabels.email eq Emails.id) and (EmailLabels.label inList hasLabels)
                }
            )
        }

        return predicate
    }
}

/**
 * What the query parameters ask for, or 400.
 *
 * Takes the parameters rather than the call: the listing socket is handed the same names in a
 * client message and has no query string to read them out of, and a filter that is built twice is
 * a filter that drifts.
 *
 * Read out by hand rather than through one helper per kind: the OpenAPI plugin walks into
 * everything a route handler reaches and falls over a project helper it meets there more than
 * once -- the same reason `http/api/` has a reader per kind instead of one generic one.
 */
internal fun mailFilter(parameters: Parameters): MailFilter {
    // Absent is no restriction; present but empty is a set with nothing in it, and the two are
    // the difference between every mail and none.
    val idSets = mutableMapOf<String, Set<Uuid>>()
    val selfIn = mutableSetOf<String>()
    for (name in listOf("imap_account_ids", "sent_by", "sent_to", "has_labels")) {
        val raw = parameters[name] ?: continue

        val ids = mutableSetOf<Uuid>()
        for (part in raw.split(",")) {
            val value = part.trim()
            if (value.isEmpty()) continue

            // The one value in a correspondent filter that is not an id, see [SELF_ADDRESSES];
            // anywhere else it is as much a mistake as any other thing that is not an id.
            if (value == SELF_ADDRESSES && (name == "sent_by" || name == "sent_to")) {
                selfIn.add(name)
                continue
            }

            val id = runCatching { Uuid.parse(value) }.getOrNull()
            ids.add(id ?: invalidRequest(name, "is not an id", value))
        }
        idSets[name] = ids
    }

    val correspondents = { name: String ->
        val ids = idSets[name]
        if (ids == null) null else Correspondents(ids = ids, self = name in selfIn)
    }

    val archivedRaw = parameters["archived_state"]
    val archivedState = if (archivedRaw == null) null else {
        val states = mutableSetOf<EmailArchiveAction>()
        for (part in archivedRaw.split(",")) {
            val value = part.trim()
            if (value.isEmpty()) continue

            val state = EmailArchiveAction.entries.firstOrNull { entry -> entry.name == value }
            states.add(
                state ?: invalidRequest(
                    "archived_state",
                    "is not one of " + EmailArchiveAction.entries.joinToString(", "),
                    value,
                )
            )
        }
        states
    }

    val readRaw = parameters["read_state"]
    val readState = when (readRaw) {
        null -> null
        "true" -> true
        "false" -> false
        else -> invalidRequest("read_state", "is not true or false", readRaw)
    }

    return MailFilter(
        archivedState = archivedState,
        readState = readState,
        imapAccountIds = idSets["imap_account_ids"],
        sentBy = correspondents("sent_by"),
        sentTo = correspondents("sent_to"),
        hasLabels = idSets["has_labels"],
    )
}

/**
 * The address book entries that are this account's own addresses: the logins of its imap
 * accounts, or of [accounts] alone when a filter named some.
 *
 * Compared in lowercase on both sides -- an address is not case sensitive, and the two sides are
 * written by different hands: one by whoever set up the account, the other by whoever sent the
 * mail.
 */
private fun ownCorrespondents(accounts: Set<Uuid>?): Query {
    val logins = ImapAccounts
        .select(ImapAccounts.username.lowerCase())
        .where { ImapAccounts.user eq EmailUsers.user }
        .let { query -> if (accounts == null) query else query.andWhere { ImapAccounts.id inList accounts } }

    return EmailUsers
        .select(EmailUsers.id)
        .where { EmailUsers.address.lowerCase() inSubQuery logins }
}
