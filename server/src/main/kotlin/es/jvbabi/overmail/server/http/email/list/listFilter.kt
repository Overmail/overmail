package es.jvbabi.overmail.server.http.email.list

import es.jvbabi.overmail.server.database.models.EmailArchiveAction
import es.jvbabi.overmail.server.database.models.EmailLabels
import es.jvbabi.overmail.server.database.models.EmailRecipients
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.emailArchiveStateIs
import es.jvbabi.overmail.server.http.api.invalidRequest
import io.ktor.server.application.ApplicationCall
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll

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
    /** Mails from any of them. */
    val sentBy: Set<Uuid>?,
    /** Mails addressed to any of them, in any of the header fields -- To, Cc and Bcc alike. */
    val sentTo: Set<Uuid>?,
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

        if (sentBy != null) predicate = predicate and (Emails.sender inList sentBy)

        // Exists rather than a join: a mail addressed to two of them is still one mail, and a
        // join would put it in the listing twice.
        if (sentTo != null) {
            predicate = predicate and exists(
                EmailRecipients.selectAll().where {
                    (EmailRecipients.email eq Emails.id) and (EmailRecipients.emailUser inList sentTo)
                }
            )
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
 * Read out by hand rather than through one helper per kind: the OpenAPI plugin walks into
 * everything a route handler reaches and falls over a project helper it meets there more than
 * once -- the same reason `http/api/` has a reader per kind instead of one generic one.
 */
internal fun ApplicationCall.mailFilter(): MailFilter {
    val parameters = request.queryParameters

    // Absent is no restriction; present but empty is a set with nothing in it, and the two are
    // the difference between every mail and none.
    val idSets = mutableMapOf<String, Set<Uuid>>()
    for (name in listOf("imap_account_ids", "sent_by", "sent_to", "has_labels")) {
        val raw = parameters[name] ?: continue

        val ids = mutableSetOf<Uuid>()
        for (part in raw.split(",")) {
            val value = part.trim()
            if (value.isEmpty()) continue

            val id = runCatching { Uuid.parse(value) }.getOrNull()
            ids.add(id ?: invalidRequest(name, "is not an id", value))
        }
        idSets[name] = ids
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
        sentBy = idSets["sent_by"],
        sentTo = idSets["sent_to"],
        hasLabels = idSets["has_labels"],
    )
}
