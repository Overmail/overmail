package es.jvbabi.overmail.page.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.ArrowClockwise
import com.phosphor.icons.regular.CaretDown
import com.phosphor.icons.regular.PaperPlaneTilt
import com.phosphor.icons.regular.Plus
import es.jvbabi.overmail.domain.model.AiChatMessage
import es.jvbabi.overmail.page.LocalBottomNavBarHeight
import es.jvbabi.overmail.openUrl
import es.jvbabi.overmail.page.agent.components.ToolCallGroup
import es.jvbabi.overmail.page.agent.markdown.MarkdownActions
import es.jvbabi.overmail.page.agent.markdown.MarkdownContent
import es.jvbabi.overmail.page.agent.markdown.parseMarkdown
import es.jvbabi.overmail.ui.components.orb.OrbState
import es.jvbabi.overmail.ui.components.orb.ThinkingOrb
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import overmail.app.shared.generated.resources.Res
import overmail.app.shared.generated.resources.agent_empty
import overmail.app.shared.generated.resources.agent_error_load
import overmail.app.shared.generated.resources.agent_error_retry
import overmail.app.shared.generated.resources.agent_error_send
import overmail.app.shared.generated.resources.agent_retry
import overmail.app.shared.generated.resources.agent_input_placeholder
import overmail.app.shared.generated.resources.agent_link_cancel
import overmail.app.shared.generated.resources.agent_link_description
import overmail.app.shared.generated.resources.agent_link_open
import overmail.app.shared.generated.resources.agent_link_title
import overmail.app.shared.generated.resources.agent_new_chat
import overmail.app.shared.generated.resources.agent_send
import overmail.app.shared.generated.resources.agent_title_new
import overmail.app.shared.generated.resources.agent_title_unnamed
import kotlin.uuid.Uuid

/**
 * The assistant. [ask] is a question asked from elsewhere -- the search -- and is sent as a new
 * chat once; [onAsked] says it is done, and the request is taken back. [onOpenEmail] opens a mail
 * an answer names.
 */
@Composable
fun AgentScreen(
    ask: String? = null,
    onAsked: () -> Unit = {},
    onOpenEmail: (Uuid) -> Unit = {},
) {
    val viewModel = koinViewModel<AgentViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Waits for the account: a question sent before it is known would go nowhere.
    LaunchedEffect(ask, state.account) {
        if (ask == null || state.account == null) return@LaunchedEffect
        viewModel.onEvent(AgentEvent.Ask(ask))
        onAsked()
    }

    // A link leads out of the app, and its text is the agent's: it is asked about before it opens.
    var pendingLink by remember { mutableStateOf<String?>(null) }
    val actions = remember(onOpenEmail) { MarkdownActions(onOpenEmail = onOpenEmail, onOpenLink = { pendingLink = it }) }

    AgentContent(state = state, actions = actions, onEvent = viewModel::onEvent)

    pendingLink?.let { url ->
        AlertDialog(
            onDismissRequest = { pendingLink = null },
            title = { Text(stringResource(Res.string.agent_link_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.agent_link_description))
                    Text(url, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingLink = null
                    openUrl(url)
                }) { Text(stringResource(Res.string.agent_link_open)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingLink = null }) { Text(stringResource(Res.string.agent_link_cancel)) }
            },
        )
    }
}

@Composable
private fun AgentContent(
    state: AgentState,
    actions: MarkdownActions,
    onEvent: (AgentEvent) -> Unit,
) {
    val bottomNavBarHeight = LocalBottomNavBarHeight.current
    val listState = rememberLazyListState()

    // Follows the end of the conversation as it grows, a new message and a longer answer alike.
    val lastLength = (state.messages.lastOrNull() as? AiChatMessage.Assistant)?.content?.length
    LaunchedEffect(state.messages.size, lastLength) {
        if (state.messages.isNotEmpty()) listState.scrollToItem(state.messages.lastIndex, Int.MAX_VALUE)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .imePadding(),
    ) {
        ChatHeader(state = state, onEvent = onEvent)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.messages.isEmpty() && state.currentChatId == null) Text(
                text = stringResource(Res.string.agent_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.messages, key = { it.id.toString() }) { message ->
                    when (message) {
                        is AiChatMessage.User -> UserMessage(message)
                        is AiChatMessage.Assistant -> AssistantMessage(
                            message = message,
                            references = state.references,
                            actions = actions,
                            canRetry = !state.isAnswering,
                            onRetry = { onEvent(AgentEvent.Retry(message.id)) },
                        )
                    }
                }
            }
        }

        state.error?.let { error ->
            Text(
                text = stringResource(
                    when (error) {
                        AgentError.Load -> Res.string.agent_error_load
                        AgentError.Send -> Res.string.agent_error_send
                        AgentError.Retry -> Res.string.agent_error_retry
                    }
                ),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                // The nav bar lies over the bottom of the screen; the prompt sits above it.
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = bottomNavBarHeight + 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.input,
                onValueChange = { onEvent(AgentEvent.SetInput(it)) },
                placeholder = { Text(stringResource(Res.string.agent_input_placeholder)) },
                maxLines = 5,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { onEvent(AgentEvent.Send) },
                enabled = !state.isAnswering && state.input.isNotBlank(),
            ) {
                Icon(PhIcons.Regular.PaperPlaneTilt, contentDescription = stringResource(Res.string.agent_send))
            }
        }
    }
}

/** What chat is open, the others to switch to, and a new one. */
@Composable
private fun ChatHeader(state: AgentState, onEvent: (AgentEvent) -> Unit) {
    var showChats by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { showChats = true }) {
                Text(
                    text = when {
                        state.currentChatId == null -> stringResource(Res.string.agent_title_new)
                        else -> state.currentChat?.name ?: stringResource(Res.string.agent_title_unnamed)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(PhIcons.Regular.CaretDown, contentDescription = null, modifier = Modifier.padding(start = 4.dp))
            }
            DropdownMenu(expanded = showChats, onDismissRequest = { showChats = false }) {
                state.chats.forEach { chat ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = chat.name ?: stringResource(Res.string.agent_title_unnamed),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = {
                            showChats = false
                            onEvent(AgentEvent.SelectChat(chat.id))
                        },
                    )
                }
            }
        }
        IconButton(onClick = { onEvent(AgentEvent.NewChat) }) {
            Icon(PhIcons.Regular.Plus, contentDescription = stringResource(Res.string.agent_new_chat))
        }
    }
}

@Composable
private fun UserMessage(message: AiChatMessage.User) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            text = message.text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** The answer's markdown, and the tool calls between it as their collapsible groups. */
@Composable
private fun AssistantMessage(
    message: AiChatMessage.Assistant,
    references: AgentReferences,
    actions: MarkdownActions,
    /** False while an answer runs in this chat: a retry now would collide with it. */
    canRetry: Boolean,
    onRetry: () -> Unit,
) {
    val blocks = remember(message.content) { agentBlocks(message.content) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEachIndexed { index, block ->
            when (block) {
                is AgentBlock.Text -> {
                    // Only the end of an answer being written can be half a tag.
                    val streaming = message.isPending && index == blocks.lastIndex
                    val markdown = remember(block.content, streaming) { parseMarkdown(block.content, streaming) }
                    MarkdownContent(markdown, references, actions)
                }
                // The last block of an answer that is still being written is what runs right now.
                is AgentBlock.Tools -> ToolCallGroup(
                    calls = block.calls,
                    references = references,
                    isRunning = message.isPending && index == blocks.lastIndex,
                )
            }
        }
        // Before the first block the agent is working on something it has not named yet.
        if (message.isPending && blocks.lastOrNull() !is AgentBlock.Tools) ThinkingOrb(OrbState.Working)

        // Only a finished answer with something in it can be acted on. It comes in after the
        // answer, so it reads as a consequence of it rather than as part of it.
        val hasActions = !message.isPending && message.content.isNotBlank()
        AnimatedVisibility(
            visible = hasActions,
            enter = fadeIn(tween(150)) + slideInVertically(tween(150)) { it / 4 },
            exit = fadeOut(tween(100)),
        ) {
            IconButton(
                onClick = onRetry,
                enabled = canRetry,
                colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = PhIcons.Regular.ArrowClockwise,
                    contentDescription = stringResource(Res.string.agent_retry),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
