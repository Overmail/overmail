package es.jvbabi.overmail.page.home.components.filter

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.*
import es.jvbabi.overmail.domain.model.ArchivedState
import es.jvbabi.overmail.domain.model.Correspondent
import es.jvbabi.overmail.domain.model.ViewFilter
import es.jvbabi.overmail.domain.model.ViewState
import es.jvbabi.overmail.ui.theme.AppTheme
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import overmail.app.shared.generated.resources.*
import kotlin.uuid.Uuid

/**
 * The filter chips above a listing, each one showing what [viewState] has set for it.
 *
 * The read and archive chips take their one click themselves, the way the web app's do; their
 * carets and the labels chip leave the picking to whoever opens on [onReadMenuClick],
 * [onArchiveMenuClick] and [onLabelsClick]. The other pickers do not exist yet.
 */
@Composable
fun ViewController(
    viewState: ViewState,
    onFilterChange: (ViewFilter) -> Unit,
    modifier: Modifier = Modifier,
    /** The labels of the filter's `hasLabels`, in its order. */
    pickedLabels: List<PickedLabel> = emptyList(),
    onLabelsClick: () -> Unit = {},
    /**
     * What the read chip has ticked. Kept apart from [ViewFilter.readState], which cannot tell
     * both states from neither; see [readStateOf].
     */
    readSelection: List<ReadState> = emptyList(),
    onReadSelectionChange: (List<ReadState>) -> Unit = {},
    onReadMenuClick: () -> Unit = {},
    onArchiveMenuClick: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(),
) {
    val filter = viewState.filter

    Row(
        modifier = modifier
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LabelsChip(
            picked = pickedLabels,
            onClick = onLabelsClick,
        )
        ToggleFilterChip(
            states = readFilterStates(),
            selected = readSelection,
            onSelectedChange = onReadSelectionChange,
            icon = PhIcons.Regular.Eyeglasses,
            onMenuClick = onReadMenuClick,
        )
        ToggleFilterChip(
            states = archiveFilterStates(),
            // Null restricts nothing, so the chip reads it as nothing picked, the way the web does.
            selected = filter.archivedState.orEmpty(),
            onSelectedChange = { onFilterChange(filter.copy(archivedState = it.ifEmpty { null })) },
            icon = PhIcons.Regular.Archive,
            onMenuClick = onArchiveMenuClick,
            unset = ARCHIVE_UNSET,
            primary = ArchivedState.Archive,
            quickLabel = stringResource(Res.string.home_filter_archive_quick),
        )
        PickerChip(
            text = stringResource(Res.string.home_filter_from),
            icon = PhIcons.Regular.User,
            active = filter.sentBy != null,
        )
        PickerChip(
            text = stringResource(Res.string.home_filter_to),
            icon = PhIcons.Regular.Users,
            active = filter.sentTo != null,
        )
        PickerChip(
            text = stringResource(Res.string.home_filter_accounts),
            icon = PhIcons.Regular.Envelope,
            active = filter.imapAccountIds != null,
        )
    }
}

/**
 * Says which labels the filter is on, the first few by name: "Labels: Uni, HPI und 2 weitere"
 * answers what is filtered at a glance, a count only that something is.
 */
@Composable
private fun LabelsChip(
    picked: List<PickedLabel>,
    onClick: () -> Unit,
) {
    val names = picked.map { it.name ?: "…" }

    val text = if (names.isEmpty()) stringResource(Res.string.home_filter_labels)
    else stringResource(Res.string.home_filter_labels_active, summarisePicked(names))

    Chip(
        text = text,
        arrowDown = true,
        leading = { ChipIcon(PhIcons.Regular.Tag) },
        active = names.isNotEmpty(),
        onClick = onClick,
    )
}

/** How many picked names a chip spells out before it counts the rest. */
private const val SHOWN_NAMES = 2

/**
 * A chip over ids the app cannot name yet -- labels, people, accounts. It says whether the filter
 * is set, not what it is set to.
 */
@Composable
private fun PickerChip(
    text: String,
    icon: ImageVector,
    active: Boolean,
) {
    Chip(
        text = text,
        arrowDown = true,
        leading = { ChipIcon(icon) },
        active = active,
    )
}

/**
 * What a chip says about what it is on: the first few by name and how many are left over, the web
 * app's `summarisePicked`. Names rather than a count alone, since the chip is read at a glance;
 * the rest is a number, since the chip sits in a row and cannot grow with the selection.
 */
@Composable
internal fun summarisePicked(names: List<String>): String {
    // One over the limit is spelled out rather than summarised: "A, B und ein weiteres" is longer
    // than "A, B, C" and says less.
    if (names.size <= SHOWN_NAMES + 1) return names.joinToString(", ")
    val rest = names.size - SHOWN_NAMES
    return names.take(SHOWN_NAMES).joinToString(", ") + " " +
        pluralStringResource(Res.plurals.home_filter_more, rest, rest)
}

@Composable
internal fun ChipIcon(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
    )
}

/**
 * One preview's frame. The state is held here, so the chips' own clicks work in interactive mode.
 */
@Composable
private fun ViewControllerPreviewFrame(filter: ViewFilter, darkTheme: Boolean = false) {
    var viewState by remember { mutableStateOf(ViewState(filter = filter)) }
    var readSelection by remember {
        mutableStateOf(
            when (filter.readState) {
                true -> listOf(ReadState.Read)
                false -> listOf(ReadState.Unread)
                null -> emptyList()
            }
        )
    }

    // Not the dynamic scheme: a preview should look the same wherever it is rendered.
    AppTheme(darkTheme = darkTheme, dynamicColor = false) {
        ViewController(
            viewState = viewState,
            onFilterChange = { viewState = viewState.copy(filter = it) },
            contentPadding = PaddingValues(16.dp),
            pickedLabels = viewState.filter.hasLabels.orEmpty().map { PickedLabel(it, "Uni", null) },
            readSelection = readSelection,
            onReadSelectionChange = { readSelection = it },
        )
    }
}

private val PREVIEW_ID = Uuid.parse("00000000-0000-0000-0000-000000000001")

/** What a fresh [ViewState] holds: the archive chip reads it as "Alle". */
@Composable
@Preview
private fun ViewControllerNothingSetPreview() {
    ViewControllerPreviewFrame(ViewFilter())
}

/** The inbox alone, which is the archive chip not being set: every chip is quiet. */
@Composable
@Preview
private fun ViewControllerInboxPreview() {
    ViewControllerPreviewFrame(ViewFilter(archivedState = listOf(ArchivedState.Unarchive)))
}

@Composable
@Preview
private fun ViewControllerUnreadPreview() {
    ViewControllerPreviewFrame(
        ViewFilter(readState = false, archivedState = listOf(ArchivedState.Unarchive))
    )
}

/** Both one-click filters taken: read mail, the archived ones too. */
@Composable
@Preview
private fun ViewControllerReadAndArchivedTooPreview() {
    ViewControllerPreviewFrame(
        ViewFilter(
            readState = true,
            archivedState = listOf(ArchivedState.Unarchive, ArchivedState.Archive),
        )
    )
}

/** What the menu can pick beyond the one click: the chip names it instead. */
@Composable
@Preview
private fun ViewControllerArchivedAndSpamPreview() {
    ViewControllerPreviewFrame(
        ViewFilter(archivedState = listOf(ArchivedState.Archive, ArchivedState.Spam))
    )
}

@Composable
@Preview
private fun ViewControllerSpamOnlyPreview() {
    ViewControllerPreviewFrame(ViewFilter(archivedState = listOf(ArchivedState.Spam)))
}

/** The picker chips set, which they can only say, not name yet. */
@Composable
@Preview
private fun ViewControllerPickersPreview() {
    ViewControllerPreviewFrame(
        ViewFilter(
            archivedState = listOf(ArchivedState.Unarchive),
            hasLabels = listOf(PREVIEW_ID),
            sentBy = listOf(Correspondent.Self),
            imapAccountIds = listOf(PREVIEW_ID),
        )
    )
}

private val EVERYTHING_SET = ViewFilter(
    readState = false,
    archivedState = listOf(ArchivedState.Unarchive, ArchivedState.Archive),
    imapAccountIds = listOf(PREVIEW_ID),
    sentBy = listOf(Correspondent.Contact(PREVIEW_ID)),
    sentTo = listOf(Correspondent.Self),
    hasLabels = listOf(PREVIEW_ID),
)

@Composable
@Preview
private fun ViewControllerEverythingSetPreview() {
    ViewControllerPreviewFrame(EVERYTHING_SET)
}

@Composable
@Preview
private fun ViewControllerEverythingSetDarkPreview() {
    ViewControllerPreviewFrame(EVERYTHING_SET, darkTheme = true)
}
