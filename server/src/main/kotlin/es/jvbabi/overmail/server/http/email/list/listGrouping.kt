package es.jvbabi.overmail.server.http.email.list

import es.jvbabi.overmail.server.database.models.EmailArchiveAction
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.emailArchiveStateIs
import es.jvbabi.overmail.server.http.api.invalidRequest
import io.ktor.http.Parameters
import kotlin.uuid.Uuid
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import org.jetbrains.exposed.v1.core.Case
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.stringLiteral
import org.jetbrains.exposed.v1.core.times
import org.jetbrains.exposed.v1.datetime.Date
import org.jetbrains.exposed.v1.datetime.Month
import org.jetbrains.exposed.v1.datetime.Year

/**
 * What a listing can be cut by, one level of it.
 *
 * The four date kinds all count *days* here, and the client folds those days into the stretch a
 * reader is shown -- a year, a month, or today and yesterday. Same reason as before: which day a
 * mail belongs to is a question of a time zone, and what that day is called is a question of
 * wording; neither is this endpoint's. A level that counts days can still sit above another one,
 * because the client folds what it is given rather than asking again.
 */
enum class MailGroupingKind(val wire: String) {
    DATE_SMART("date_smart"),
    YEAR("year"),
    MONTH("month"),
    DAY("day"),
    SENDER("sender"),
    IMAP_ACCOUNT("imap_account"),
    READ("read"),
    ARCHIVED("archived"),
}

/**
 * What identifies a group at this level, as one value per mail.
 *
 * It goes into the `group by` and comes back out as the key of a group, so it has to be something
 * a client can send back to ask for the mails under it -- see [MailGroupingKind.groupPredicate].
 */
fun MailGroupingKind.groupKey(): Expression<*> = when (this) {
    // Suppressed, not outdated: kotlinx' `Instant` is a typealias of the one in `kotlin.time`
    // now, which makes the deprecated overload and its replacement the same signature.
    @Suppress("DEPRECATION")
    MailGroupingKind.DAY -> Date(Emails.sent)

    @Suppress("DEPRECATION")
    MailGroupingKind.YEAR -> Year(Emails.sent)

    // One number for both halves of a month, so the key stays a single value: 2026-09 is 202609.
    @Suppress("DEPRECATION")
    MailGroupingKind.MONTH -> monthNumber()

    // The stretches a reader is shown, cut where `SmartDateBucket` says; the four near ones are
    // small numbers, everything older is the month it was sent in.
    MailGroupingKind.DATE_SMART -> {
        val boundaries = smartDateBoundaries()

        @Suppress("DEPRECATION")
        var case = Case().When(Emails.sent greaterEq boundaries.today, intLiteral(SmartDateBucket.TODAY))
        @Suppress("DEPRECATION")
        case = case.When(Emails.sent greaterEq boundaries.yesterday, intLiteral(SmartDateBucket.YESTERDAY))
        boundaries.week?.let { start ->
            @Suppress("DEPRECATION")
            case = case.When(Emails.sent greaterEq start, intLiteral(SmartDateBucket.WEEK))
        }
        boundaries.month?.let { start ->
            @Suppress("DEPRECATION")
            case = case.When(Emails.sent greaterEq start, intLiteral(SmartDateBucket.MONTH))
        }

        case.Else(monthNumber())
    }

    MailGroupingKind.SENDER -> Emails.sender
    MailGroupingKind.IMAP_ACCOUNT -> Emails.imapAccount
    MailGroupingKind.READ -> Emails.isRead

    // The archive state is an event log, not a column, so the state a mail is *in* is worked out
    // per mail; see `emailArchiveStateIs`. Unarchive is the else branch because it is what a mail
    // with no event at all is.
    MailGroupingKind.ARCHIVED -> Case()
        .When(emailArchiveStateIs(EmailArchiveAction.Archive), stringLiteral(EmailArchiveAction.Archive.name))
        .When(emailArchiveStateIs(EmailArchiveAction.Spam), stringLiteral(EmailArchiveAction.Spam.name))
        .Else(stringLiteral(EmailArchiveAction.Unarchive.name))
}

/**
 * The mails under the group [key] of this level, as a predicate over [Emails].
 *
 * The counterpart of [groupKey]: what a client sends back after reading the groups, to ask for
 * the rows of one of them. A key that means nothing at this level is a 400 rather than an empty
 * group -- an empty group is a group that exists.
 */
fun MailGroupingKind.groupPredicate(key: String): Op<Boolean> = when (this) {
    MailGroupingKind.DAY -> {
        val day = runCatching { LocalDate.parse(key) }.getOrNull()
            ?: invalidRequest("group", "is not a day", key)

        // The server's own days, like everywhere a date is cut here -- the counts were made the
        // same way, so a client asking for the day it was told about gets exactly those mails.
        val zone = TimeZone.currentSystemDefault()
        (Emails.sent greaterEq day.atStartOfDayIn(zone)) and
                (Emails.sent less day.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone))
    }

    MailGroupingKind.YEAR -> {
        val year = key.toIntOrNull() ?: invalidRequest("group", "is not a year", key)
        val zone = TimeZone.currentSystemDefault()

        (Emails.sent greaterEq LocalDate(year, 1, 1).atStartOfDayIn(zone)) and
                (Emails.sent less LocalDate(year + 1, 1, 1).atStartOfDayIn(zone))
    }

    MailGroupingKind.MONTH -> monthRange(key)

    MailGroupingKind.DATE_SMART -> {
        val bucket = key.toIntOrNull() ?: invalidRequest("group", "is not a stretch", key)
        val boundaries = smartDateBoundaries()

        when (bucket) {
            SmartDateBucket.TODAY -> Emails.sent greaterEq boundaries.today
            SmartDateBucket.YESTERDAY ->
                (Emails.sent greaterEq boundaries.yesterday) and (Emails.sent less boundaries.today)

            SmartDateBucket.WEEK -> {
                val start = boundaries.week ?: invalidRequest("group", "is no stretch today", key)
                (Emails.sent greaterEq start) and (Emails.sent less boundaries.yesterday)
            }

            SmartDateBucket.MONTH -> {
                val start = boundaries.month ?: invalidRequest("group", "is no stretch today", key)
                (Emails.sent greaterEq start) and (Emails.sent less (boundaries.week ?: boundaries.yesterday))
            }

            // A calendar month, and only the part of it the stretches above did not already take.
            else -> monthRange(key) and (Emails.sent less boundaries.older)
        }
    }

    MailGroupingKind.SENDER -> {
        val id = runCatching { Uuid.parse(key) }.getOrNull() ?: invalidRequest("group", "is not an id", key)
        Emails.sender eq id
    }

    MailGroupingKind.IMAP_ACCOUNT -> {
        val id = runCatching { Uuid.parse(key) }.getOrNull() ?: invalidRequest("group", "is not an id", key)
        Emails.imapAccount eq id
    }

    MailGroupingKind.READ -> when (key) {
        "true" -> Emails.isRead eq true
        "false" -> Emails.isRead eq false
        else -> invalidRequest("group", "is not true or false", key)
    }

    MailGroupingKind.ARCHIVED -> {
        val state = EmailArchiveAction.entries.firstOrNull { entry -> entry.name == key }
            ?: invalidRequest("group", "is not an archive state", key)
        emailArchiveStateIs(state)
    }
}

/**
 * The levels `by` asks for, outermost first, or 400.
 *
 * No directions and no order of the groups themselves: the client holds every group it was told
 * about, so it is the one that puts them in order -- by a name it resolved, or by a bucket it
 * folded days into. Sending a direction as well would be two places deciding the same thing.
 */
internal fun mailGroupings(parameters: Parameters): List<MailGroupingKind> {
    val raw = parameters["by"] ?: return emptyList()

    val kinds = mutableListOf<MailGroupingKind>()
    for (part in raw.split(",")) {
        val value = part.trim()
        if (value.isEmpty()) continue

        val kind = MailGroupingKind.entries.firstOrNull { entry -> entry.wire == value }
            ?: invalidRequest("by", "is not a grouping", value)
        if (kind in kinds) invalidRequest("by", "names the same grouping twice", value)

        kinds.add(kind)
    }

    return kinds
}

/**
 * The one group `group` names, as a predicate over [Emails]: one key per level of [groupings],
 * in the same order.
 *
 * Without the parameter it is the whole listing -- an ungrouped table asks for its rows that way,
 * and so does anything that wants a group's ancestors rather than the group itself. A key count
 * that does not match the levels is a 400: a client that has the groups has the keys.
 */
internal fun mailGroup(parameters: Parameters, groupings: List<MailGroupingKind>): Op<Boolean> {
    val raw = parameters["group"] ?: return Op.TRUE

    val keys = raw.split(",").map { part -> part.trim() }.filter { part -> part.isNotEmpty() }
    if (keys.size > groupings.size) {
        invalidRequest("group", "names more levels than `by` does", raw)
    }

    var predicate: Op<Boolean> = Op.TRUE
    for ((index, key) in keys.withIndex()) predicate = predicate and groupings[index].groupPredicate(key)

    return predicate
}

/** A month as one number, which is what a month key is: 2026-09 is 202609. */
@Suppress("DEPRECATION")
private fun monthNumber() = Year(Emails.sent) times intLiteral(100) plus Month(Emails.sent)

/** The mails sent in the month [key] names, as `yyyymm`. */
private fun monthRange(key: String): Op<Boolean> {
    val number = key.toIntOrNull() ?: invalidRequest("group", "is not a month", key)
    val year = number / 100
    val month = number % 100
    if (month !in 1..12) invalidRequest("group", "is not a month", key)

    val zone = TimeZone.currentSystemDefault()
    val start = LocalDate(year, month, 1).atStartOfDayIn(zone)
    val next = if (month == 12) LocalDate(year + 1, 1, 1) else LocalDate(year, month + 1, 1)

    return (Emails.sent greaterEq start) and (Emails.sent less next.atStartOfDayIn(zone))
}
