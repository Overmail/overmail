package es.jvbabi.overmail.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.intl.Locale
import es.jvbabi.overmail.formatDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A send time the way the web app's mail table shows it: today it is only a time, everything
 * older a day and month, with the year once it is another one. Which day that is exactly is the
 * group heading's to say.
 *
 * Calendar days in the device's time zone, not 24 hour windows: a mail from 23:30 is another
 * day's half an hour later. A send time in the future is a date.
 */
@Composable
fun sentAtLabel(sentAt: Instant): String {
    val languageTag = Locale.current.toLanguageTag()
    // Worked out as the row is composed rather than kept ticking, like the web: a list that is
    // open at midnight showing a time for yesterday's mail for a while is nobody's problem.
    return remember(sentAt, languageTag) {
        val timeZone = TimeZone.currentSystemDefault()
        val sent = sentAt.toLocalDateTime(timeZone).date
        val today = Clock.System.todayIn(timeZone)
        val skeleton = when {
            sent == today -> "jjmm"
            sent.year == today.year -> "dMMM"
            else -> "dMMMyy"
        }
        formatDateTime(sentAt, skeleton, languageTag)
    }
}
