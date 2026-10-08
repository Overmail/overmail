package es.jvbabi.overmail.page.agent.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.ArrowClockwise
import es.jvbabi.overmail.domain.model.AiChatMessage
import es.jvbabi.overmail.hapticTick
import es.jvbabi.overmail.page.agent.AgentBlock
import es.jvbabi.overmail.page.agent.AgentReferences
import es.jvbabi.overmail.page.agent.agentBlocks
import es.jvbabi.overmail.page.agent.markdown.MarkdownActions
import es.jvbabi.overmail.page.agent.markdown.MarkdownContent
import es.jvbabi.overmail.page.agent.markdown.parseMarkdown
import es.jvbabi.overmail.ui.components.orb.OrbSize
import es.jvbabi.overmail.ui.components.orb.OrbState
import es.jvbabi.overmail.ui.components.orb.ThinkingOrb
import es.jvbabi.overmail.ui.theme.displayFontFamily
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import overmail.app.shared.generated.resources.Res
import overmail.app.shared.generated.resources.agent_empty_title
import overmail.app.shared.generated.resources.agent_retry
import overmail.app.shared.generated.resources.agent_suggestion_summary
import overmail.app.shared.generated.resources.agent_suggestion_today
import overmail.app.shared.generated.resources.agent_suggestion_waiting
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** Closer together than this, text arriving is felt as one tick: a tick per token would hum. */
private val TICK_INTERVAL = 70.milliseconds
private const val TICK_INTENSITY = 0.18f
/** A tool call landing, or the answer being done: a little more than a word. */
private const val STEP_INTENSITY = 0.45f

/** A question: a quiet bubble on the right, as wide as it needs and no wider than most of the line. */
@Composable
fun UserMessage(message: AiChatMessage.User, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Box(Modifier.fillMaxWidth(0.85f), contentAlignment = Alignment.CenterEnd) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * An answer: no bubble, the whole width, the way one reads a page. Its tool calls between the
 * text as their collapsible groups, and once it is done, what can be done with it.
 *
 * While it is written, every bit of text that arrives is felt as the faintest tick, and a tool
 * call or the end a little more -- the hand follows the writing without the eyes having to.
 */
@Composable
fun AssistantMessage(
    message: AiChatMessage.Assistant,
    references: AgentReferences,
    actions: MarkdownActions,
    /** False while an answer runs in this chat: a retry now would collide with it. */
    canRetry: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(message.content) { agentBlocks(message.content) }
    StreamingHaptics(message, blocks)

    // Answers are read, not glanced at: a little more air between the lines than the default.
    val typography = MaterialTheme.typography
    val readingTypography = remember(typography) {
        typography.copy(bodyLarge = typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 26.sp))
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MaterialTheme(typography = readingTypography) {
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
        }

        // Before the first block, and while text is being written: the agent is at work.
        if (message.isPending && blocks.lastOrNull() !is AgentBlock.Tools) ThinkingOrb(OrbState.Working)

        // Only a finished answer with something in it can be acted on. It comes in after the
        // answer, so it reads as a consequence of it rather than as part of it.
        AnimatedVisibility(
            visible = !message.isPending && message.content.isNotBlank(),
            enter = fadeIn(tween(200, delayMillis = 100)) + slideInVertically(tween(200, delayMillis = 100)) { it / 3 },
            exit = fadeOut(tween(100)),
        ) {
            Row(Modifier.padding(top = 2.dp)) {
                MessageAction(
                    label = Res.string.agent_retry,
                    enabled = canRetry,
                    onClick = onRetry,
                ) { Icon(PhIcons.Regular.ArrowClockwise, contentDescription = null, modifier = Modifier.size(16.dp)) }
            }
        }
    }
}

@Composable
private fun MessageAction(label: StringResource, enabled: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = stringResource(label), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (enabled) colors.onSurfaceVariant else colors.onSurfaceVariant.copy(alpha = 0.38f)
        ) { icon() }
    }
}

/** The ticks of [message] while it is written; nothing for an answer that is already done. */
@Composable
private fun StreamingHaptics(message: AiChatMessage.Assistant, blocks: List<AgentBlock>) {
    // Only an answer seen being finished ends with a tick: one opened finished, or a chat left
    // while one was running, ends nothing.
    var wasPending by remember(message.id) { mutableStateOf(message.isPending) }
    LaunchedEffect(message.isPending) {
        if (wasPending && !message.isPending) hapticTick(STEP_INTENSITY)
        wasPending = message.isPending
    }

    if (!message.isPending) return
    val length by rememberUpdatedState(message.content.length)
    val tools by rememberUpdatedState(blocks.sumOf { (it as? AgentBlock.Tools)?.calls?.size ?: 0 })

    LaunchedEffect(message.id) {
        var lastLength = length
        var lastTools = tools
        var lastTick = TimeSource.Monotonic.markNow()
        snapshotFlow { length to tools }.collect { (length, tools) ->
            val grew = length > lastLength
            val toolLanded = tools > lastTools
            lastLength = length
            lastTools = tools
            when {
                toolLanded -> hapticTick(STEP_INTENSITY)
                grew && lastTick.elapsedNow() >= TICK_INTERVAL -> hapticTick(TICK_INTENSITY)
                else -> return@collect
            }
            lastTick = TimeSource.Monotonic.markNow()
        }
    }
}

/**
 * A chat without a question yet: the orb breathing, a line that invites, and a few things worth
 * asking -- a tap asks them right away.
 */
@Composable
fun EmptyChat(onAsk: (String) -> Unit, modifier: Modifier = Modifier) {
    val suggestions = listOf(
        stringResource(Res.string.agent_suggestion_today),
        stringResource(Res.string.agent_suggestion_summary),
        stringResource(Res.string.agent_suggestion_waiting),
    )
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ThinkingOrb(OrbState.Breathing, size = OrbSize.Avatar)
        Text(
            text = stringResource(Res.string.agent_empty_title),
            style = MaterialTheme.typography.headlineMedium,
            fontFamily = displayFontFamily(),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 20.dp, bottom = 28.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            suggestions.forEach { suggestion ->
                Text(
                    text = suggestion,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .clickable { onAsk(suggestion) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}
