package es.jvbabi.overmail.server.http.email.list

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Where the stretches of a smart date listing begin, which is what a header stands over. */
class SmartDateTest {

    private val zone = TimeZone.UTC

    @Test
    fun `today and yesterday are always their own`() {
        // A Thursday.
        val boundaries = boundariesOn(LocalDate(2026, 9, 17))

        assertEquals(LocalDate(2026, 9, 17).atStartOfDayIn(zone), boundaries.today)
        assertEquals(LocalDate(2026, 9, 16).atStartOfDayIn(zone), boundaries.yesterday)
    }

    @Test
    fun `the rest of the week only exists once the week has one`() {
        // Monday and Tuesday are today and yesterday, so there is nothing else of that week yet.
        assertNull(boundariesOn(LocalDate(2026, 9, 14)).week)
        assertNull(boundariesOn(LocalDate(2026, 9, 15)).week)

        // Wednesday: Monday is neither today nor yesterday any more.
        assertEquals(
            LocalDate(2026, 9, 14).atStartOfDayIn(zone),
            boundariesOn(LocalDate(2026, 9, 16)).week,
        )
    }

    @Test
    fun `the rest of the month is only what the stretches above have not taken`() {
        // Thursday the 17th: the week stretch reaches back to Monday the 14th, so the 1st to the
        // 13th are the rest of the month.
        assertEquals(
            LocalDate(2026, 9, 1).atStartOfDayIn(zone),
            boundariesOn(LocalDate(2026, 9, 17)).month,
        )

        // The 2nd: yesterday is already the 1st, so there is no rest.
        assertNull(boundariesOn(LocalDate(2026, 9, 2)).month)

        // Thursday the 3rd: the week already starts in August, so it covers the 1st and 2nd and
        // there is nothing left for a month stretch either.
        assertNull(boundariesOn(LocalDate(2026, 9, 3)).month)
    }

    @Test
    fun `everything below the last stretch is counted by its month`() {
        // The month stretch is the lowest one that applies, so the calendar months start under it.
        val thursday = boundariesOn(LocalDate(2026, 9, 17))
        assertEquals(thursday.month, thursday.older)

        // And where neither applies -- Tuesday the 1st, so the week is still August and
        // yesterday is already the month before -- yesterday is the floor.
        val first = boundariesOn(LocalDate(2026, 9, 1))
        assertNull(first.week)
        assertNull(first.month)
        assertEquals(first.yesterday, first.older)
    }

    private fun boundariesOn(date: LocalDate) =
        smartDateBoundaries(zone, LocalDateTime(date, kotlinx.datetime.LocalTime(12, 0)).toInstant(zone))
}
