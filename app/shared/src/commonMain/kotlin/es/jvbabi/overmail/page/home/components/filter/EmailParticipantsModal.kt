@file:OptIn(ExperimentalMaterial3Api::class)

package es.jvbabi.overmail.page.home.components.filter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.Envelope
import es.jvbabi.overmail.domain.model.Correspondent
import es.jvbabi.overmail.domain.model.OvermailAccount
import es.jvbabi.overmail.domain.model.Participant
import es.jvbabi.overmail.page.home.components.filter.label_search.Item
import es.jvbabi.overmail.page.home.components.filter.label_search.LabelTextField
import es.jvbabi.overmail.page.home.components.filter.label_search.PickedItem
import es.jvbabi.overmail.page.home.components.filter.label_search.SearchDivider
import es.jvbabi.overmail.ui.components.ParticipantAvatar
import es.jvbabi.overmail.ui.theme.AppTheme
import es.jvbabi.overmail.utils.fuzzyContains
import org.jetbrains.compose.resources.stringResource
import overmail.app.shared.generated.resources.Res
import overmail.app.shared.generated.resources.home_participants_empty
import overmail.app.shared.generated.resources.home_participants_search
import overmail.app.shared.generated.resources.home_participants_self
import kotlin.uuid.Uuid

/**
 * Picks who a from or to filter is on, the web app's `SenderFilter`: the label picker with people
 * in it -- the picked ones as removable badges in the field, a list below that toggles. A row
 * shows a face, the name and the address under it, and above the correspondents sits the
 * account's own addresses, [Correspondent.Self].
 *
 * Nothing about views: it is told what it is called ([title]) and says what was toggled.
 *
 * @param known the picked correspondents as far as they are cached, by id.
 */
@Composable
fun ParticipantModal(
    visible: Boolean,
    title: String,
    picked: List<Correspondent>,
    known: Map<Uuid, Participant>,
    query: String,
    results: List<Participant>,
    /** Whether the server's answer is still on its way; [results] are the cache's until then. */
    isFetching: Boolean,
    onQueryChange: (String) -> Unit,
    /** A row of the list was picked. */
    onToggle: (Correspondent) -> Unit,
    /** A badge was taken out of the field, by its X or Backspace. */
    onRemove: (Correspondent) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(visible) {
        if (visible) state.show() else state.hide()
    }

    // See GroupModal: composed only while it is meant to be seen or still sliding out.
    if (!visible && !state.isVisible) return

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        modifier = Modifier.fillMaxSize(),
        dragHandle = null,
        // The bottom inset is the list's own, so it scrolls behind the navigation bar.
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal) },
    ) {
        ParticipantPickerContent(
            title = title,
            picked = picked,
            known = known,
            query = query,
            results = results,
            isFetching = isFetching,
            onQueryChange = onQueryChange,
            onToggle = onToggle,
            onRemove = onRemove,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Where [Correspondent.Self] stands among the ids the shared field works with. Not an address
 * book id, and no server id is ever nil.
 */
private val SELF_ID = Uuid.NIL

private fun Correspondent.pickerId(): Uuid = when (this) {
    is Correspondent.Contact -> id
    Correspondent.Self -> SELF_ID
}

private fun correspondentOf(id: Uuid): Correspondent =
    if (id == SELF_ID) Correspondent.Self else Correspondent.Contact(id)

@Composable
fun ParticipantPickerContent(
    title: String,
    picked: List<Correspondent>,
    known: Map<Uuid, Participant>,
    query: String,
    results: List<Participant>,
    isFetching: Boolean,
    onQueryChange: (String) -> Unit,
    onToggle: (Correspondent) -> Unit,
    onRemove: (Correspondent) -> Unit,
    modifier: Modifier = Modifier,
    focusOnStart: Boolean = true,
) {
    val focusRequester = remember { FocusRequester() }
    val haptics = LocalHapticFeedback.current

    // The list is driven by the field, so opening lands in it.
    if (focusOnStart) LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val pickedIds = remember(picked) { picked.map { it.pickerId() }.toSet() }
    val selfName = stringResource(Res.string.home_participants_self)
    // The account's own entry is offered like a search result: while nothing is typed, or when
    // what is typed matches it.
    val selfMatches = query.isBlank() || selfName fuzzyContains query.trim()

    val toggle = { correspondent: Correspondent ->
        haptics.performHapticFeedback(
            if (correspondent.pickerId() in pickedIds) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn
        )
        onToggle(correspondent)
    }

    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp),
        )

        LabelTextField(
            focusRequester = focusRequester,
            picked = picked.map { correspondent ->
                when (correspondent) {
                    Correspondent.Self -> PickedItem(
                        id = SELF_ID,
                        name = selfName,
                        color = MaterialTheme.colorScheme.secondary,
                        // No face: this one is not a person but every address the account sends from.
                        leading = { SelfIcon(Modifier.size(14.dp)) },
                    )
                    is Correspondent.Contact -> {
                        val participant = known[correspondent.id]
                        val name = participant?.displayName ?: "…"
                        PickedItem(
                            id = correspondent.id,
                            name = name,
                            color = MaterialTheme.colorScheme.secondary,
                            // The face rather than a color: it is what tells two badges apart here.
                            leading = { ParticipantAvatar(participant, size = 14.dp, fallbackName = name) },
                        )
                    }
                }
            },
            placeholder = stringResource(Res.string.home_participants_search),
            query = query,
            onRemove = { onRemove(correspondentOf(it)) },
            onQueryChange = onQueryChange,
        )

        SearchDivider(isFetching = isFetching)

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = 8.dp,
                end = 8.dp,
                top = 8.dp,
                bottom = 8.dp + WindowInsets.navigationBars.union(WindowInsets.ime).asPaddingValues().calculateBottomPadding(),
            ),
        ) {
            // Above the correspondents: the one entry that is not somebody, and the one most
            // listings are about.
            if (selfMatches) item(key = "self") {
                Item(
                    name = selfName,
                    color = MaterialTheme.colorScheme.primary,
                    subtitle = null,
                    emailCount = null,
                    picked = SELF_ID in pickedIds,
                    onClick = { toggle(Correspondent.Self) },
                    leading = { SelfIcon(Modifier.size(20.dp)) },
                )
            }

            // Not while the server may still find somebody the cache does not have.
            if (results.isEmpty() && !isFetching) item(key = "empty") {
                Text(
                    text = stringResource(Res.string.home_participants_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }

            items(results, key = { it.id.toString() }) { participant ->
                Item(
                    name = participant.displayName,
                    color = MaterialTheme.colorScheme.primary,
                    // The address under the name, because two people share a name more often
                    // than an address; one without a name is their address alone.
                    subtitle = if (participant.name != null) participant.email else null,
                    emailCount = participant.emailCount,
                    picked = participant.id in pickedIds,
                    onClick = { toggle(Correspondent.Contact(participant.id)) },
                    leading = { ParticipantAvatar(participant, size = 24.dp) },
                )
            }
        }
    }
}

@Composable
private fun SelfIcon(modifier: Modifier) {
    Icon(imageVector = PhIcons.Regular.Envelope, contentDescription = null, modifier = modifier)
}

@Composable
@Preview
private fun ParticipantPickerContentPreview() {
    val participants = listOf(
        PreviewParticipant("University of Waterloo", "uninews@uwaterloo.com", 128),
        PreviewParticipant("Google Account", "account@google.com", 42),
        PreviewParticipant(null, "nsmith@hotmail.com", 1),
    ).mapIndexed { index, (name, email, count) ->
        Participant(
            id = Uuid.fromLongs(0, index.toLong() + 1),
            name = name,
            email = email,
            emailCount = count,
            avatarUrl = null,
            avatarPadding = null,
            overmailAccount = PREVIEW_ACCOUNT,
        )
    }
    var picked by remember { mutableStateOf(listOf<Correspondent>(Correspondent.Self, Correspondent.Contact(participants[0].id))) }
    var query by remember { mutableStateOf("") }

    AppTheme(dynamicColor = false) {
        Surface {
            ParticipantPickerContent(
                title = "From",
                picked = picked,
                known = participants.associateBy { it.id },
                query = query,
                results = participants,
                isFetching = true,
                onQueryChange = { query = it },
                onToggle = { c -> picked = if (c in picked) picked - c else picked + c },
                onRemove = { c -> picked = picked - c },
                modifier = Modifier.height(400.dp),
                focusOnStart = false,
            )
        }
    }
}

private data class PreviewParticipant(val name: String?, val email: String, val count: Long)

private val PREVIEW_ACCOUNT = OvermailAccount(
    id = Uuid.fromLongs(0, 0),
    username = "preview",
    firstName = "Preview",
    lastName = "User",
    email = "preview@example.com",
    homeserver = "https://example.com",
    token = "",
)
