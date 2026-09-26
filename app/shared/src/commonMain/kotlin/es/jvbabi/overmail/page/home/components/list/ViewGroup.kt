package es.jvbabi.overmail.page.home.components.list

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import es.jvbabi.overmail.domain.model.ArchivedState
import es.jvbabi.overmail.domain.model.Participant
import es.jvbabi.overmail.domain.repository.ViewResult
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import overmail.app.shared.generated.resources.*
import kotlin.time.Clock

@Composable
fun ViewGroupComponent(
    group: ViewResult.Group,
    /** The sender a [ViewResult.Group.Sender] stands for, null while it is not known here. */
    sender: Participant? = null,
) {
    Column {
        Row(
            modifier = Modifier
                .padding(top = 8.dp)
                .padding(horizontal = 8.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = groupLabel(group, sender),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.alignByBaseline(),
            )
            Text(
                text = group.emailCount.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.alignByBaseline(),
            )
        }
    }
}

@Composable
private fun groupLabel(group: ViewResult.Group, sender: Participant?): String = when (group) {
    is ViewResult.Group.DateSmart -> when (val stretch = group.stretch) {
        ViewResult.Group.DateSmart.Stretch.Today -> stringResource(Res.string.home_group_today)
        ViewResult.Group.DateSmart.Stretch.Yesterday -> stringResource(Res.string.home_group_yesterday)
        ViewResult.Group.DateSmart.Stretch.Week -> stringResource(Res.string.home_group_week)
        ViewResult.Group.DateSmart.Stretch.Month -> stringResource(Res.string.home_group_month)
        is ViewResult.Group.DateSmart.Stretch.CalendarMonth -> monthLabel(stretch.year, stretch.month)
    }
    is ViewResult.Group.Month -> monthLabel(group.year, group.month)
    is ViewResult.Group.Year -> group.year.toString()
    is ViewResult.Group.Day -> dayLabel(group.date)
    is ViewResult.Group.Sender -> sender?.name ?: sender?.email ?: stringResource(Res.string.home_group_sender_unknown)
    is ViewResult.Group.ImapAccount -> group.imapAccount.username
    is ViewResult.Group.Read -> stringResource(if (group.isRead) Res.string.home_group_read else Res.string.home_group_unread)
    is ViewResult.Group.Archived -> stringResource(
        when (group.state) {
            ArchivedState.Unarchive -> Res.string.home_filter_archive_inbox
            ArchivedState.Archive -> Res.string.home_filter_archive_archived
            ArchivedState.Spam -> Res.string.home_filter_archive_spam
        }
    )
}

/** The year only when it is another one; within this year the month names itself. */
@Composable
private fun monthLabel(year: Int, month: Month): String {
    val name = stringResource(month.nameResource())
    val thisYear = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()).year }
    return if (year == thisYear) name else stringResource(Res.string.home_group_month_with_year, name, year)
}

@Composable
private fun dayLabel(date: LocalDate): String =
    stringResource(Res.string.home_group_day, stringResource(date.month.nameResource()), date.day, date.year)

private fun Month.nameResource(): StringResource = when (this) {
    Month.JANUARY -> Res.string.common_month_january
    Month.FEBRUARY -> Res.string.common_month_february
    Month.MARCH -> Res.string.common_month_march
    Month.APRIL -> Res.string.common_month_april
    Month.MAY -> Res.string.common_month_may
    Month.JUNE -> Res.string.common_month_june
    Month.JULY -> Res.string.common_month_july
    Month.AUGUST -> Res.string.common_month_august
    Month.SEPTEMBER -> Res.string.common_month_september
    Month.OCTOBER -> Res.string.common_month_october
    Month.NOVEMBER -> Res.string.common_month_november
    Month.DECEMBER -> Res.string.common_month_december
}
