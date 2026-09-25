package es.jvbabi.overmail.common.email.grouping

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime

/**
 * From this day of the week on there is a stretch of the week that is neither today nor
 * yesterday. Before that, every day of this week already *is* one of those two.
 */
private const val WEEK_BUCKET_FROM_ISO_DAY = 3

/**
 * The stretches a listing grouped by `date_smart` falls into: today, yesterday, the rest of this
 * week, the rest of this month, and then month by month.
 *
 * Numbers rather than words, because they are the key of a group and a group key is one value per
 * mail: the four near ones are counted from [TODAY], anything older is its calendar month as
 * `year * 100 + month`. What they are *called* is the client's -- see `mails.groups.*` there --
 * and so is which of them a reader wants to see first.
 *
 * Cut here rather than in the client, unlike the day counts this replaced: a group is what a
 * client asks the api for, so the boundary between two of them has to be one both sides agree on.
 * The server's own zone, like everywhere a date is grouped here.
 */
object SmartDateBucket {
    const val TODAY = 1
    const val YESTERDAY = 2
    const val WEEK = 3
    const val MONTH = 4

    /** The lowest number a calendar month can be, which is what tells the two apart. */
    const val FIRST_MONTH = 100

    fun ofMonth(year: Int, month: Int): Int = year * 100 + month
}

/**
 * Where the stretches of [SmartDateBucket] begin, worked out once for a request.
 *
 * Null for a boundary that does not apply right now: on a Monday there is no "rest of the week"
 * between today and yesterday, and in the first days of a month the week already reaches the 1st,
 * so there is no "rest of the month" either. A boundary that is null is a stretch that does not
 * exist today, which is what keeps a header from standing over nothing.
 */
data class SmartDateBoundaries(
    val today: Instant,
    val yesterday: Instant,
    val week: Instant?,
    val month: Instant?,
) {
    /**
     * Where the named stretches end and the calendar months begin: everything before this is in
     * the month it was sent in.
     */
    val older: Instant get() = month ?: week ?: yesterday
}

/** The boundaries as they stand at [now] in [zone]. */
fun smartDateBoundaries(
    zone: TimeZone = TimeZone.currentSystemDefault(),
    now: Instant = Clock.System.now(),
): SmartDateBoundaries {
    val date = now.toLocalDateTime(zone).date
    val today = date.atStartOfDayIn(zone)
    val yesterday = LocalDate.fromEpochDays(date.toEpochDays() - 1).atStartOfDayIn(zone)

    val isoDay = date.dayOfWeek.isoDayNumber
    val week = if (isoDay < WEEK_BUCKET_FROM_ISO_DAY) null
    else LocalDate.fromEpochDays(date.toEpochDays() - (isoDay - 1)).atStartOfDayIn(zone)

    val firstOfMonth = LocalDate(date.year, date.month, 1).atStartOfDayIn(zone)
    // Only when the stretches above have not already reached the 1st: a month stretch that holds
    // nothing is a header over nothing.
    val covered = week ?: yesterday
    val month = if (covered <= firstOfMonth) null else firstOfMonth

    return SmartDateBoundaries(today = today, yesterday = yesterday, week = week, month = month)
}
