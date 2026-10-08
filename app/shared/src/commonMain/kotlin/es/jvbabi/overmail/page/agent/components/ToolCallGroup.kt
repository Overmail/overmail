package es.jvbabi.overmail.page.agent.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.BookOpenText
import com.phosphor.icons.regular.Brain
import com.phosphor.icons.regular.CaretRight
import com.phosphor.icons.regular.Eyeglasses
import com.phosphor.icons.regular.MagnifyingGlass
import com.phosphor.icons.regular.PencilSimple
import com.phosphor.icons.regular.Tag
import com.phosphor.icons.regular.TagSimple
import com.phosphor.icons.regular.Wrench
import es.jvbabi.overmail.page.agent.AgentReferences
import es.jvbabi.overmail.page.agent.ToolCall
import es.jvbabi.overmail.page.agent.markdown.shortSubject
import es.jvbabi.overmail.ui.components.LabelBadge
import es.jvbabi.overmail.ui.components.ParticipantAvatar
import es.jvbabi.overmail.ui.components.orb.OrbState
import es.jvbabi.overmail.ui.components.orb.ThinkingOrb
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import overmail.app.shared.generated.resources.Res
import overmail.app.shared.generated.resources.agent_tool_calls
import overmail.app.shared.generated.resources.agent_tool_create_label
import overmail.app.shared.generated.resources.agent_tool_deleted
import overmail.app.shared.generated.resources.agent_tool_expand
import overmail.app.shared.generated.resources.agent_tool_label_email
import overmail.app.shared.generated.resources.agent_tool_read_email
import overmail.app.shared.generated.resources.agent_tool_read_knowledge
import overmail.app.shared.generated.resources.agent_tool_rename_chat
import overmail.app.shared.generated.resources.agent_tool_search_emails
import overmail.app.shared.generated.resources.agent_tool_search_knowledge
import overmail.app.shared.generated.resources.agent_tool_search_sender
import overmail.app.shared.generated.resources.agent_tool_search_subject
import overmail.app.shared.generated.resources.agent_tool_thinking
import overmail.app.shared.generated.resources.agent_tool_unlabel_email
import overmail.app.shared.generated.resources.agent_tool_update_knowledge
import overmail.app.shared.generated.resources.agent_tool_working
import overmail.app.shared.generated.resources.agent_tool_write_knowledge
import kotlin.uuid.Uuid

/** The size of the orb or wrench in the header, and where the detail rows are indented to. */
private val LEAD_SIZE = 20.dp
private val LEAD_GAP = 8.dp
private val DETAIL_ICON_SIZE = 16.dp

/** An orb per kind of work, so the line says what is going on without reading it. */
private val ORBS = mapOf(
    "search-emails" to OrbState.Searching,
    "search-knowledge" to OrbState.Searching,
    "thinking" to OrbState.Breathing,
)

private val LABELS = mapOf(
    "search-emails" to Res.string.agent_tool_search_emails,
    "read-email" to Res.string.agent_tool_read_email,
    "create-label" to Res.string.agent_tool_create_label,
    "label-email" to Res.string.agent_tool_label_email,
    "unlabel-email" to Res.string.agent_tool_unlabel_email,
    "search-knowledge" to Res.string.agent_tool_search_knowledge,
    "read-knowledge" to Res.string.agent_tool_read_knowledge,
    "write-knowledge" to Res.string.agent_tool_write_knowledge,
    "rename-chat" to Res.string.agent_tool_rename_chat,
    "thinking" to Res.string.agent_tool_thinking,
)

/**
 * The tool calls that ran between two pieces of an answer, as one collapsible group; the web
 * app's `ToolCallGroup.svelte`.
 *
 * Collapsed it is a single line: while the group is the last thing in an answer that is still
 * being written, the orb of the call that is running; once the answer went on, a wrench and how
 * many calls it took. Expanded, a line per call underneath.
 */
@Composable
fun ToolCallGroup(
    calls: List<ToolCall>,
    references: AgentReferences,
    /** Nothing follows this group yet, so its last call is what the agent is doing now. */
    isRunning: Boolean,
    modifier: Modifier = Modifier,
) {
    var isExpanded by rememberSaveable { mutableStateOf(false) }
    val current = calls.last()
    val caretRotation by animateFloatAsState(if (isExpanded) 90f else 0f, tween(200))

    val label = if (isRunning) stringResource(LABELS[current.kind] ?: Res.string.agent_tool_working)
    else pluralStringResource(Res.plurals.agent_tool_calls, calls.size, calls.size)

    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
        Column(modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(Res.string.agent_tool_expand),
                    ) { isExpanded = !isExpanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LEAD_GAP),
            ) {
                if (isRunning) ThinkingOrb(ORBS[current.kind] ?: OrbState.Working)
                else Icon(
                    imageVector = PhIcons.Regular.Wrench,
                    contentDescription = null,
                    modifier = Modifier.size(LEAD_SIZE).padding(2.dp),
                )

                // The two lines pass each other inside the row instead of pushing it apart.
                AnimatedContent(
                    targetState = label,
                    transitionSpec = {
                        (slideInVertically(tween(220)) { it / 2 } + fadeIn(tween(220))) togetherWith
                            (slideOutVertically(tween(220)) { -it / 2 } + fadeOut(tween(220)))
                    },
                    modifier = Modifier.weight(1f),
                ) { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Icon(
                    imageVector = PhIcons.Regular.CaretRight,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp).rotate(caretRotation),
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(tween(200)) + fadeIn(tween(200)),
                exit = shrinkVertically(tween(200)) + fadeOut(tween(200)),
            ) {
                // Indented to where the label starts, so the rows read as its detail.
                Column(
                    modifier = Modifier.padding(start = LEAD_SIZE + LEAD_GAP, top = 2.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    calls.forEach { call -> ToolCallDetail(call, references) }
                }
            }
        }
    }
}

/** One call of an expanded group. A call this app does not know, or one missing its ids, shows nothing. */
@Composable
private fun ToolCallDetail(call: ToolCall, references: AgentReferences) {
    when (call.kind) {
        "thinking" -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DetailIcon(PhIcons.Regular.Brain, Modifier.padding(top = 2.dp))
            Text(
                text = buildString {
                    append(stringResource(Res.string.agent_tool_thinking))
                    if (call.content.isNotEmpty()) append(" ").append(call.content)
                },
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = if (call.content.isNotEmpty()) FontStyle.Italic else null,
            )
        }

        "read-email" -> {
            val emailId = call.uuid("emailId") ?: return
            val email = references.emails[emailId]
            DetailRow(PhIcons.Regular.Eyeglasses, Res.string.agent_tool_read_email) {
                if (email != null) ParticipantAvatar(email.sentBy, size = DETAIL_ICON_SIZE)
                Subject(shortSubject(call.attribute("subject") ?: email?.subject.orEmpty()))
            }
        }

        "search-emails" -> DetailRow(PhIcons.Regular.MagnifyingGlass, Res.string.agent_tool_search_emails) {
            call.attribute("subject")?.let { Muted(stringResource(Res.string.agent_tool_search_subject, it)) }
            call.attribute("sender")?.let { Muted(stringResource(Res.string.agent_tool_search_sender, it)) }
        }

        "create-label" -> {
            val labelId = call.uuid("labelId") ?: return
            DetailRow(PhIcons.Regular.Tag, Res.string.agent_tool_create_label) {
                LabelChip(labelId, references)
            }
        }

        "label-email", "unlabel-email" -> {
            val labelId = call.uuid("labelId") ?: return
            val emailId = call.uuid("emailId") ?: return
            val detached = call.kind == "unlabel-email"
            DetailRow(
                icon = if (detached) PhIcons.Regular.TagSimple else PhIcons.Regular.Tag,
                label = if (detached) Res.string.agent_tool_unlabel_email else Res.string.agent_tool_label_email,
            ) {
                LabelChip(labelId, references)
                // A separator rather than a word: "on" and "from" would be fragments to translate.
                Muted("·")
                EmailChip(emailId, references)
            }
        }

        "search-knowledge" -> DetailRow(PhIcons.Regular.MagnifyingGlass, Res.string.agent_tool_search_knowledge) {
            // An empty query is the agent asking what is known at all; then there is nothing to show.
            call.attribute("query")?.let { Subject(it) }
        }

        "read-knowledge" -> DetailRow(PhIcons.Regular.BookOpenText, Res.string.agent_tool_read_knowledge) {
            call.attribute("name")?.let { Subject(it) }
        }

        "write-knowledge" -> DetailRow(
            icon = PhIcons.Regular.Brain,
            // A write that landed on an entry that was already there rewrote it.
            label = if (call.attribute("replaced") == "true") Res.string.agent_tool_update_knowledge
            else Res.string.agent_tool_write_knowledge,
        ) {
            call.attribute("name")?.let { Subject(it) }
        }

        "rename-chat" -> {
            val name = call.attribute("name") ?: return
            DetailRow(PhIcons.Regular.PencilSimple, Res.string.agent_tool_rename_chat) { Subject(name) }
        }
    }
}

/** An icon, what happened, and what it happened to -- wrapping rather than reaching past the edge. */
@Composable
private fun DetailRow(icon: ImageVector, label: StringResource, content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        DetailIcon(icon)
        Muted(stringResource(label))
        content()
    }
}

@Composable
private fun DetailIcon(icon: ImageVector, modifier: Modifier = Modifier) {
    Icon(imageVector = icon, contentDescription = null, modifier = modifier.size(DETAIL_ICON_SIZE))
}

@Composable
private fun Muted(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium)
}

/** What a call was about, in the text color: it is the part worth reading. */
@Composable
private fun Subject(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A label by id as the badge it is everywhere else; one that is not there anymore says so. */
@Composable
private fun LabelChip(labelId: Uuid, references: AgentReferences) {
    val label = references.labels[labelId]
    LabelBadge(
        name = label?.name ?: stringResource(Res.string.agent_tool_deleted),
        color = label?.color,
        small = false,
    )
}

/** A mail by id: its sender's picture and its subject. */
@Composable
private fun EmailChip(emailId: Uuid, references: AgentReferences) {
    val email = references.emails[emailId]
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (email != null) ParticipantAvatar(email.sentBy, size = DETAIL_ICON_SIZE)
        Box { Subject(email?.subject?.let(::shortSubject) ?: stringResource(Res.string.agent_tool_deleted)) }
    }
}

private fun ToolCall.uuid(name: String): Uuid? = attribute(name)?.let(Uuid::parseOrNull)
