package es.jvbabi.overmail.page.home.components.filter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import es.jvbabi.overmail.domain.model.ArchivedState
import es.jvbabi.overmail.ui.components.Checkbox
import es.jvbabi.overmail.ui.components.FloatingModal
import es.jvbabi.overmail.utils.pressable
import org.jetbrains.compose.resources.stringResource
import overmail.app.shared.generated.resources.*

/** One state a filter can be on: what the caller stores for it, and what it is called. */
data class FilterState<T>(val value: T, val label: String)

/**
 * A filter over a fixed set of states as a split chip, the web app's `ToggleFilter`: the button
 * turns the filter on and off, the caret beside it opens the states to tick one by one.
 *
 * Not set is what the quiet chip means, and what the button gets back to. Usually that is nothing
 * picked -- an empty set restricts nothing -- but a filter can start somewhere else: the archive
 * one is unset at the inbox, because that is what a mailbox is. [unset] is what says which.
 *
 * @param states every state, in the order the menu lists them and the chip names them.
 * @param primary what the button adds to [unset], the one thing the filter is mostly used for.
 * @param quickLabel what the chip calls that one click; the primary state's name by default.
 */
@Composable
fun <T> ToggleFilterChip(
    states: List<FilterState<T>>,
    selected: List<T>,
    onSelectedChange: (List<T>) -> Unit,
    icon: ImageVector,
    onMenuClick: () -> Unit,
    unset: List<T> = emptyList(),
    primary: T = states.first().value,
    quickLabel: String? = null,
) {
    val selectedSet = selected.toSet()
    val unsetSet = unset.toSet()

    /** Whatever [unset] holds is not a filter, and the chip says so by staying quiet. */
    val active = selectedSet != unsetSet
    /** Exactly the one click the button offers, and nothing else. */
    val isQuick = selectedSet == unsetSet + primary

    // What is on beyond [unset] -- "Posteingang, Archiviert" says no more than "Archiviert" does.
    // A selection with no such difference falls back to what is actually picked.
    val named = states.filter { it.value in selectedSet && it.value !in unsetSet }
        .ifEmpty { states.filter { it.value in selectedSet } }

    val text = when {
        // Not set reads as the offer, the way a button says what it does, and the offer taken up
        // reads as itself rather than as the states behind it.
        !active || isQuick -> quickLabel ?: states.first { it.value == primary }.label
        // Set to nothing at all, which lets everything through.
        named.isEmpty() -> stringResource(Res.string.home_filter_all)
        else -> summarisePicked(named.map { it.label })
    }

    Chip(
        text = text,
        arrowDown = true,
        segmented = true,
        leading = { ChipIcon(icon) },
        active = active,
        // Set goes back to unset, whatever the menu picked: the button says "this filter", not
        // "this state".
        onClick = { onSelectedChange(states.ordered(if (active) unset else unset + primary)) },
        onSegmentedClick = onMenuClick,
    )
}

/**
 * The states of a [ToggleFilterChip] to tick one by one, in a floating sheet that is as tall as
 * they are. It stays open: ticking one state is rarely all somebody came for.
 */
@Composable
fun <T> FilterStatesModal(
    visible: Boolean,
    title: String,
    states: List<FilterState<T>>,
    selected: List<T>,
    onSelectedChange: (List<T>) -> Unit,
    onDismiss: () -> Unit,
) {
    FloatingModal(visible = visible, onDismiss = onDismiss) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
        states.forEach { state ->
            val checked = state.value in selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pressable(haptic = { if (checked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn }) {
                        onSelectedChange(states.ordered(if (checked) selected - state.value else selected + state.value))
                    }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(15.dp))
                Text(text = state.label, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/**
 * [values] in the order the states are declared, so the chip reads the same however they were
 * picked.
 */
private fun <T> List<FilterState<T>>.ordered(values: Collection<T>): List<T> =
    map { it.value }.filter { it in values }

/** What a mail can be as far as the read filter goes. */
enum class ReadState { Unread, Read }

/**
 * What `ViewFilter.readState` is for the ticked states: true for read alone, false for unread
 * alone, null for both or neither. Every mail is one or the other, so naming both restricts
 * nothing; the chip keeps the two apart because they are different things to say.
 */
fun readStateOf(selected: List<ReadState>): Boolean? {
    val read = ReadState.Read in selected
    val unread = ReadState.Unread in selected
    return if (read == unread) null else read
}

/** Unread first: it is what the chip offers on its own, and what this filter is for. */
@Composable
fun readFilterStates(): List<FilterState<ReadState>> = listOf(
    FilterState(ReadState.Unread, stringResource(Res.string.home_filter_read_unread)),
    FilterState(ReadState.Read, stringResource(Res.string.home_filter_read_read)),
)

/**
 * The inbox first: it is where a listing starts, and the menu reads as the listing grows from it.
 * Spam last -- it is looked for on purpose.
 */
@Composable
fun archiveFilterStates(): List<FilterState<ArchivedState>> = listOf(
    FilterState(ArchivedState.Unarchive, stringResource(Res.string.home_filter_archive_inbox)),
    FilterState(ArchivedState.Archive, stringResource(Res.string.home_filter_archive_archived)),
    FilterState(ArchivedState.Spam, stringResource(Res.string.home_filter_archive_spam)),
)

/** What the archive filter reads as not set: the inbox alone, which is what a mailbox is. */
val ARCHIVE_UNSET = listOf(ArchivedState.Unarchive)
