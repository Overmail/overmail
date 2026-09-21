package es.jvbabi.overmail.page.home.components.filter

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import es.jvbabi.overmail.domain.model.ImapAccount
import es.jvbabi.overmail.ui.components.Checkbox
import es.jvbabi.overmail.ui.components.FloatingModal
import es.jvbabi.overmail.ui.components.FloatingModalDefaults
import es.jvbabi.overmail.utils.pressable
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import overmail.app.shared.generated.resources.*
import kotlin.uuid.Uuid

/**
 * Picks the mailboxes a filter is on, the web app's `InboxFilter`: somebody has a handful of them,
 * not a directory, so there is nothing to search -- the rows are simply all of them, in a floating
 * card only as tall as they are.
 *
 * What was read last stays while the fresh list is on its way, so the card does not blink; only
 * with nothing read yet are there placeholder rows, and a failure is only told when there is
 * nothing to show instead.
 */
@Composable
fun ImapAccountsModal(
    visible: Boolean,
    accounts: List<ImapAccount>,
    selected: List<Uuid>,
    isFetching: Boolean,
    /** The server could not be asked and [accounts] is all the cache had. */
    failed: Boolean,
    onToggle: (Uuid) -> Unit,
    onDismiss: () -> Unit,
) {
    FloatingModal(visible = visible, onDismiss = onDismiss) {
        Text(
            text = stringResource(Res.string.home_filter_accounts),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        )

        AnimatedVisibility(
            visible = isFetching && accounts.isNotEmpty(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            LinearProgressIndicator(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .fillMaxWidth()
                    .clip(CircleShape),
            )
        }

        when {
            accounts.isEmpty() && isFetching -> repeat(2) { PlaceholderRow() }
            accounts.isEmpty() -> Text(
                text = stringResource(if (failed) Res.string.home_accounts_failed else Res.string.home_accounts_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            )
            else -> accounts.forEach { account ->
                val checked = account.id in selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FloatingModalDefaults.ItemCornerRadius))
                        .pressable(haptic = { if (checked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn }) {
                            onToggle(account.id)
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    // The host under the login: two accounts at one provider differ by login,
                    // two logins of the same name by host.
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = account.username,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = account.host,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = pluralStringResource(Res.plurals.home_labels_email_count, account.emailCount.toInt(), account.emailCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** A row about to be one: reads better than a card that first says there is nothing. */
@Composable
private fun PlaceholderRow() {
    val shade = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)).background(shade))
        Box(Modifier.size(width = 160.dp, height = 14.dp).clip(CircleShape).background(shade))
    }
}
