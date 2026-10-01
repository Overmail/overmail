package es.jvbabi.overmail.page.home.components.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.ArrowDownLeft
import com.phosphor.icons.regular.ArrowUpRight
import com.phosphor.icons.regular.Check
import com.phosphor.icons.regular.Sparkle
import es.jvbabi.overmail.domain.model.Label
import es.jvbabi.overmail.domain.model.OvermailAccount
import es.jvbabi.overmail.domain.model.Participant
import es.jvbabi.overmail.page.home.components.filter.CHIP_ICON_SIZE
import es.jvbabi.overmail.page.home.components.filter.Chip
import es.jvbabi.overmail.page.home.components.filter.label_search.Item
import es.jvbabi.overmail.page.home.components.filter.label_search.ItemBadge
import es.jvbabi.overmail.page.home.components.filter.label_search.PickedItem
import es.jvbabi.overmail.ui.components.ParticipantAvatar
import es.jvbabi.overmail.ui.theme.AppTheme
import org.jetbrains.compose.resources.stringResource
import overmail.app.shared.generated.resources.*
import kotlin.uuid.Uuid

/**
 * Past this a section pushes the list out of sight; typing narrows it down instead. Per section,
 * so the labels and the people both get a look in.
 */
private const val MAX_SUGGESTIONS = 3

/**
 * What the search is on, right under the field: the people and labels as the same removable
 * badges the filter pickers hold in their fields, wrapping onto further lines so every one of
 * them stays in sight.
 */
@Composable
fun ActiveSearchFilters(
    state: SearchState,
    onEvent: (SearchEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val participants = ParticipantDirection.entries.flatMap { direction ->
        state.activeParticipants(direction).map { direction to it }
    }

    AnimatedVisibility(
        visible = participants.isNotEmpty() || state.activeLabels.isNotEmpty(),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .animateContentSize(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            participants.forEach { (direction, participant) ->
                key(direction, participant.id) {
                    ItemBadge(
                        item = PickedItem(
                            id = participant.id,
                            name = stringResource(
                                when (direction) {
                                    ParticipantDirection.From -> Res.string.home_filter_from_active
                                    ParticipantDirection.To -> Res.string.home_filter_to_active
                                },
                                participant.displayName,
                            ),
                            color = MaterialTheme.colorScheme.secondary,
                            leading = { ParticipantAvatar(participant, size = 14.dp) },
                        ),
                        onRemove = {
                            haptics.performHapticFeedback(HapticFeedbackType.ToggleOff)
                            onEvent(SearchEvent.RemoveParticipant(participant, direction))
                        },
                    )
                }
            }
            state.activeLabels.forEach { label ->
                key(label.id) {
                    ItemBadge(
                        item = PickedItem(id = label.id, name = label.name, color = label.color),
                        onRemove = {
                            haptics.performHapticFeedback(HapticFeedbackType.ToggleOff)
                            onEvent(SearchEvent.RemoveLabel(label))
                        },
                    )
                }
            }
        }
    }
}

/**
 * What the search offers while it is focused: asking the AI, the people and the labels that match
 * what is typed. Everything here toggles, as in the filter pickers, so a filter is turned off
 * where it was turned on.
 */
@Composable
fun SearchSuggestions(
    state: SearchState,
    onEvent: (SearchEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val participants = state.suggestedParticipants.take(MAX_SUGGESTIONS)
    val labels = state.suggestedLabels.take(MAX_SUGGESTIONS)

    // A surface of its own: the header's blur fades out towards its bottom, and the mails behind
    // would show through the lower rows.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .animateContentSize()
            // Inside the surface, so it is its rows that scroll once the room runs out.
            .verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
    ) {
        AskAiRow(query = state.query)

        if (participants.isNotEmpty()) {
            SectionTitle(stringResource(Res.string.home_search_people))
            participants.forEach { participant ->
                key(participant.id) {
                    ParticipantRow(
                        participant = participant,
                        isFrom = participant.id in state.activeSentByIds,
                        isTo = participant.id in state.activeSentToIds,
                        onToggle = { direction, wasActive ->
                            haptics.performHapticFeedback(
                                if (wasActive) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn
                            )
                            onEvent(SearchEvent.ToggleParticipant(participant, direction))
                        },
                    )
                }
            }
        }

        if (labels.isNotEmpty()) {
            SectionTitle(stringResource(Res.string.home_filter_labels))
            labels.forEach { label ->
                key(label.id) {
                    val picked = label.id in state.activeLabelIds
                    Item(
                        color = label.color,
                        name = label.name,
                        subtitle = null,
                        emailCount = label.emailCount,
                        picked = picked,
                        onClick = {
                            haptics.performHapticFeedback(
                                if (picked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn
                            )
                            onEvent(SearchEvent.ToggleLabel(label))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
    )
}

/**
 * A person, with a button for each side of a mail they can be on. The row itself does nothing:
 * which side is meant is the whole question, and a tap on the name could only guess it.
 */
@Composable
private fun ParticipantRow(
    participant: Participant,
    isFrom: Boolean,
    isTo: Boolean,
    /** The side that was tapped, and whether it was on before. */
    onToggle: (ParticipantDirection, Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ParticipantAvatar(participant, size = 24.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = participant.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (participant.name != null) Text(
                text = participant.email,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DirectionChip(
                text = stringResource(Res.string.home_filter_from),
                icon = PhIcons.Regular.ArrowDownLeft,
                active = isFrom,
                onClick = { onToggle(ParticipantDirection.From, isFrom) },
            )
            DirectionChip(
                text = stringResource(Res.string.home_filter_to),
                icon = PhIcons.Regular.ArrowUpRight,
                active = isTo,
                onClick = { onToggle(ParticipantDirection.To, isTo) },
            )
        }
    }
}

@Composable
private fun DirectionChip(text: String, icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    Chip(
        text = text,
        active = active,
        onClick = onClick,
        leading = {
            Icon(
                imageVector = if (active) PhIcons.Regular.Check else icon,
                contentDescription = null,
                modifier = Modifier.size(CHIP_ICON_SIZE),
            )
        },
    )
}

@Composable
private fun AskAiRow(query: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable {}
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = PhIcons.Regular.Sparkle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.home_search_ai_title),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = if (query.isBlank()) stringResource(Res.string.home_search_ai_example)
                else stringResource(Res.string.home_search_ai_query, query),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
@Preview
private fun SearchSuggestionsPreview() {
    val labels = listOf(
        "Uni" to Color(0xFF3B82F6),
        "Rechnungen" to Color(0xFFEF4444),
        "HPI" to Color(0xFF22C55E),
    ).mapIndexed { index, (name, color) ->
        Label(
            id = Uuid.fromLongs(0, index.toLong()),
            name = name,
            color = color,
            emailCount = 12L * (index + 1),
            overmailAccount = PREVIEW_ACCOUNT,
        )
    }
    val participants = listOf(
        "University of Waterloo" to "uninews@uwaterloo.com",
        null to "nsmith@hotmail.com",
    ).mapIndexed { index, (name, email) ->
        Participant(
            id = Uuid.fromLongs(1, index.toLong()),
            name = name,
            email = email,
            avatarUrl = null,
            avatarPadding = null,
            emailCount = 3,
            overmailAccount = PREVIEW_ACCOUNT,
        )
    }
    val state = SearchState(
        query = "u",
        suggestedLabels = labels,
        activeLabels = labels.take(1),
        suggestedParticipants = participants,
        activeSentBy = participants.take(1),
    )

    AppTheme(dynamicColor = false) {
        Surface {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ActiveSearchFilters(state = state, onEvent = {})
                SearchSuggestions(state = state, onEvent = {})
            }
        }
    }
}

private val PREVIEW_ACCOUNT = OvermailAccount(
    id = Uuid.fromLongs(0, 0),
    username = "preview",
    firstName = "Preview",
    lastName = "User",
    email = "preview@example.com",
    homeserver = "https://example.com",
    token = "",
)
