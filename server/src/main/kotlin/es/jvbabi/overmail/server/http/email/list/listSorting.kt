package es.jvbabi.overmail.server.http.email.list

import es.jvbabi.overmail.server.database.models.EmailUsers
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.http.api.invalidRequest
import io.ktor.http.Parameters
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.SortOrder

/**
 * What orders the mails inside the deepest group -- the view's `email_sorting`.
 *
 * Only the mails within one group: which group comes before which is the client's, see
 * [MailGroupingKind]. The id is the tiebreaker everywhere, so two mails that agree on the sort
 * still come back in the same order every time -- a page that could repeat or skip a row is worse
 * than an arbitrary but fixed order.
 */
enum class MailSorting(val wire: String) {
    DATE("date"),
    SENDER("sender"),
    SUBJECT("subject"),
}

/** Newest, or A-Z, first: what each sort means when it is not reversed. */
fun MailSorting.naturalOrder(): SortOrder = when (this) {
    MailSorting.DATE -> SortOrder.DESC
    MailSorting.SENDER, MailSorting.SUBJECT -> SortOrder.ASC
}

/**
 * What the mails are ordered by. The sender is ordered by the address rather than by the display
 * name a mail carried: the name is per mail, so the same correspondent would scatter through the
 * list under every spelling they ever used.
 */
fun MailSorting.orderBy(): Expression<*> = when (this) {
    MailSorting.DATE -> Emails.sent
    MailSorting.SENDER -> EmailUsers.address
    MailSorting.SUBJECT -> Emails.subject
}

/** Whether the sort needs the sender's address, which only a join has. */
fun MailSorting.needsSender(): Boolean = this == MailSorting.SENDER

/**
 * What `sort` asks for, or 400. Newest first unless something else is named.
 *
 * The parameters rather than the call, see [mailFilter].
 */
internal fun mailSorting(parameters: Parameters): Pair<MailSorting, Boolean> {
    val raw = parameters["sort"] ?: return MailSorting.DATE to false

    val reversed = raw.endsWith(":r")
    val name = if (reversed) raw.dropLast(2) else raw
    val sorting = MailSorting.entries.firstOrNull { entry -> entry.wire == name }
        ?: invalidRequest("sort", "is not a sorting", raw)

    return sorting to reversed
}
