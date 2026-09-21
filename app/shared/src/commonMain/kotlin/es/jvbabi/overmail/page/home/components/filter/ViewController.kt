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
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import overmail.app.shared.generated.resources.*
import kotlin.uuid.Uuid

/**
 * The filter chips above a listing, each one showing what [viewState] has set for it.
 *
 * The read and archive chips take their one click themselves, the way the web app's do, and hand
 * the changed filter to [onFilterChange]. The labels chip names [pickedLabels] and leaves the
 * picking to whoever opens on [onLabelsClick]; the other pickers do not exist yet.
 */
@Composable
fun ViewController(
    viewState: ViewState,
    onFilterChange: (ViewFilter) -> Unit,
    modifier: Modifier = Modifier,
    /** The labels of the filter's `hasLabels`, in its order. */
    pickedLabels: List<PickedLabel> = emptyList(),
    onLabelsClick: () -> Unit = {},
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
        ReadStateChip(
            readState = filter.readState,
            onChange = { onFilterChange(filter.copy(readState = it)) },
        )
        ArchivedStateChip(
            archivedState = filter.archivedState,
            onChange = { onFilterChange(filter.copy(archivedState = it)) },
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
    // One over the limit is spelled out rather than summarised: "A, B und ein weiteres" is longer
    // than "A, B, C" and says less.
    val shown = if (names.size <= SHOWN_NAMES + 1) names else names.take(SHOWN_NAMES)
    val rest = names.size - shown.size

    val text = if (names.isEmpty()) stringResource(Res.string.home_filter_labels)
    else stringResource(
        Res.string.home_filter_labels_active,
        shown.joinToString(", ") + if (rest == 0) "" else " " + pluralStringResource(Res.plurals.home_filter_more, rest, rest),
    )

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
 * Unset offers "Ungelesen"; set, it names the one state that is on. Both states at once are no
 * restriction, so there is no third case to show -- see [ViewFilter.readState].
 */
@Composable
private fun ReadStateChip(
    readState: Boolean?,
    onChange: (Boolean?) -> Unit,
) {
    Chip(
        text = stringResource(
            if (readState == true) Res.string.home_filter_read_read else Res.string.home_filter_read_unread
        ),
        arrowDown = true,
        segmented = true,
        leading = { ChipIcon(PhIcons.Regular.Eyeglasses) },
        active = readState != null,
        // Unread is what the button is for; a second click takes back whatever is set.
        onClick = { onChange(if (readState == null) false else null) },
    )
}

/**
 * Unset is not "everything" here but the inbox alone: a mailbox is what is left to do. The button
 * adds the archived mails to that and says so, spam stays out of that one click. Anything else
 * names what is on beyond the inbox, and a filter set to nothing at all lets every mail through.
 */
@Composable
private fun ArchivedStateChip(
    archivedState: List<ArchivedState>?,
    onChange: (List<ArchivedState>?) -> Unit,
) {
    // Null restricts nothing, so the chip reads it as nothing picked, the way the web app does.
    val selected = archivedState.orEmpty().toSet()
    val active = selected != ARCHIVE_UNSET
    val isQuick = selected == ARCHIVE_QUICK

    val named = ARCHIVE_ORDER.filter { it in selected && it !in ARCHIVE_UNSET }
        .ifEmpty { ARCHIVE_ORDER.filter { it in selected } }

    val text = when {
        !active || isQuick -> stringResource(Res.string.home_filter_archive_quick)
        named.isEmpty() -> stringResource(Res.string.home_filter_all)
        else -> named.map { stringResource(it.label) }.joinToString(", ")
    }

    Chip(
        text = text,
        arrowDown = true,
        segmented = true,
        leading = { ChipIcon(PhIcons.Regular.Archive) },
        active = active,
        // A second click on a set chip takes it back to the inbox, whatever the menu picked.
        onClick = { onChange(ARCHIVE_ORDER.filter { it in (if (active) ARCHIVE_UNSET else ARCHIVE_QUICK) }) },
    )
}

/** What the archive chip reads as not set: the inbox alone. */
private val ARCHIVE_UNSET = setOf(ArchivedState.Unarchive)

/** What the archive chip's one click turns on: the archived mails on top of the inbox. */
private val ARCHIVE_QUICK = ARCHIVE_UNSET + ArchivedState.Archive

/** The inbox first, spam last -- the order the chip names them in. */
private val ARCHIVE_ORDER = listOf(ArchivedState.Unarchive, ArchivedState.Archive, ArchivedState.Spam)

private val ArchivedState.label: StringResource
    get() = when (this) {
        ArchivedState.Unarchive -> Res.string.home_filter_archive_inbox
        ArchivedState.Archive -> Res.string.home_filter_archive_archived
        ArchivedState.Spam -> Res.string.home_filter_archive_spam
    }

@Composable
private fun ChipIcon(icon: ImageVector) {
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

    // Not the dynamic scheme: a preview should look the same wherever it is rendered.
    AppTheme(darkTheme = darkTheme, dynamicColor = false) {
        ViewController(
            viewState = viewState,
            onFilterChange = { viewState = viewState.copy(filter = it) },
            contentPadding = PaddingValues(16.dp),
            pickedLabels = viewState.filter.hasLabels.orEmpty().map { PickedLabel(it, "Uni", null) },
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
