package es.jvbabi.overmail.page.home.components.list

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import es.jvbabi.overmail.domain.model.ArchivedState
import es.jvbabi.overmail.domain.model.Participant
import es.jvbabi.overmail.domain.repository.ViewResult
import es.jvbabi.overmail.page.home.PREVIEW_GROUPS_OF_EVERY_KIND
import es.jvbabi.overmail.page.home.PREVIEW_SENDERS_BY_ID
import es.jvbabi.overmail.ui.components.ParticipantAvatar
import es.jvbabi.overmail.ui.theme.AppTheme
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import overmail.app.shared.generated.resources.*
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * A group of the listing and, one level down, the groups inside it. Deeper ones are not shown
 * on their own, only counted into the one above them.
 */
@Composable
fun ViewGroupComponent(
    modifier: Modifier = Modifier,
    group: ViewResult.Group,
    /** The correspondents sender groups stand for, on either level; a missing one is not known here. */
    senders: Map<Uuid, Participant> = emptyMap(),
) {
    Column(modifier = modifier) {
        GroupHeader(
            group = group,
            senders = senders,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp, start = 32.dp),
        )
        HorizontalDivider(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .height(1.dp)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.outline)
        )
        group.items.forEach { item ->
            when (item) {
                is ViewResult.Group -> ViewGroupComponent(
                    group = item,
                    senders = senders,
                    modifier = Modifier.padding(start = 32.dp)
                )
                is ViewResult.Item -> Row {
                    ParticipantAvatar(item.email.sentBy, size = 24.dp)
                    Text("Email ${item.email.subject}")
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(
    group: ViewResult.Group,
    senders: Map<Uuid, Participant>,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .padding(horizontal = 8.dp)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = groupLabel(group, (group as? ViewResult.Group.Sender)?.let { senders[it.participantId] }),
            style = style,
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

@Composable
@Preview
private fun ViewGroupComponentPreview() {
    AppTheme(dynamicColor = false) {
        Surface {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                PREVIEW_GROUPS_OF_EVERY_KIND.forEach { group ->
                    ViewGroupComponent(group = group, senders = PREVIEW_SENDERS_BY_ID)
                }
            }
        }
    }
}
