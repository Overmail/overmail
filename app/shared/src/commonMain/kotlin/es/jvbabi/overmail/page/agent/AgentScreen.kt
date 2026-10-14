package es.jvbabi.overmail.page.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.ArrowDown
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import es.jvbabi.overmail.domain.model.AiChatMessage
import es.jvbabi.overmail.openUrl
import es.jvbabi.overmail.page.LocalBottomNavBarHeight
import es.jvbabi.overmail.page.agent.components.AssistantMessage
import es.jvbabi.overmail.page.agent.components.ChatListSheet
import es.jvbabi.overmail.page.agent.components.ChatTopBar
import es.jvbabi.overmail.page.agent.components.Composer
import es.jvbabi.overmail.page.agent.components.EmptyChat
import es.jvbabi.overmail.page.agent.components.UserMessage
import es.jvbabi.overmail.page.agent.markdown.MarkdownActions
import es.jvbabi.overmail.utils.ProgressiveDirection
import es.jvbabi.overmail.utils.progressiveBackground
import es.jvbabi.overmail.utils.progressiveBackgroundBlur
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import overmail.app.shared.generated.resources.Res
import overmail.app.shared.generated.resources.agent_error_load
import overmail.app.shared.generated.resources.agent_error_retry
import overmail.app.shared.generated.resources.agent_error_send
import overmail.app.shared.generated.resources.agent_link_cancel
import overmail.app.shared.generated.resources.agent_link_description
import overmail.app.shared.generated.resources.agent_link_open
import overmail.app.shared.generated.resources.agent_link_title
import overmail.app.shared.generated.resources.agent_scroll_to_end
import kotlin.uuid.Uuid

/**
 * The assistant. [ask] is a question asked from elsewhere -- the search -- and is sent as a new
 * chat once; [onAsked] says it is done, and the request is taken back. [newChat] opens a new chat
 * with the cursor in the prompt the same way, see [onNewChatOpened]. [onOpenEmail] opens a mail an
 * answer names.
 */
@Composable
fun AgentScreen(
    ask: String? = null,
    onAsked: () -> Unit = {},
    newChat: Boolean = false,
    onNewChatOpened: () -> Unit = {},
    onOpenEmail: (Uuid) -> Unit = {},
) {
    val viewModel = koinViewModel<AgentViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    val promptFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(newChat) {
        if (!newChat) return@LaunchedEffect
        viewModel.onEvent(AgentEvent.NewChat)
        promptFocus.requestFocus()
        // Focus alone leaves the keyboard down when the field had it already.
        keyboard?.show()
        onNewChatOpened()
    }

    // Waits for the account: a question sent before it is known would go nowhere.
    LaunchedEffect(ask, state.account) {
        if (ask == null || state.account == null) return@LaunchedEffect
        viewModel.onEvent(AgentEvent.Ask(ask))
        onAsked()
    }

    // A link leads out of the app, and its text is the agent's: it is asked about before it opens.
    var pendingLink by remember { mutableStateOf<String?>(null) }
    val actions = remember(onOpenEmail) { MarkdownActions(onOpenEmail = onOpenEmail, onOpenLink = { pendingLink = it }) }

    AgentContent(state = state, actions = actions, promptFocus = promptFocus, onEvent = viewModel::onEvent)

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


/** How much of the area under the conversation fades in, from its top; the rest is solid. */
private const val BOTTOM_FADE = 0.22f

/**
 * The one margin of the screen: the text of the conversation, the edges of the questions and of
 * the prompt, and the icons of the bar all line up on it.
 */
internal val SCREEN_PADDING = 16.dp

/** The fade above the prompt, where the conversation runs out. */
private val BOTTOM_FADE_HEIGHT = 24.dp

/** Between the prompt and what is below it, the nav bar or the keyboard. */
private val PROMPT_GAP = 12.dp

@Composable
private fun AgentContent(
    state: AgentState,
    actions: MarkdownActions,
    promptFocus: FocusRequester,
    onEvent: (AgentEvent) -> Unit,
) {
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val hazeState = rememberHazeState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showChats by remember { mutableStateOf(false) }
    var topBarHeight by remember { mutableStateOf(0.dp) }
    var bottomHeight by remember { mutableStateOf(0.dp) }

    // Above the nav bar, or above the keyboard while it is out -- it covers the nav bar then.
    val keyboard = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val bottomOffset = maxOf(keyboard, LocalBottomNavBarHeight.current)

    // Following the end is the reader's choice: scrolling up stops it, coming back to the end --
    // or the button -- starts it again. That way an answer being written pushes the view along
    // for whoever reads its end, and leaves alone whoever went back to something further up.
    var followsEnd by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .distinctUntilChanged()
            .collect { (scrolling, canScrollForward) ->
                if (!canScrollForward) followsEnd = true
                else if (scrolling && listState.lastScrolledBackward) followsEnd = false
            }
    }
    // Another chat, or a question just sent: back to the end, whatever was scrolled before.
    val lastUserMessage = state.shownMessages.lastOrNull { it is AiChatMessage.User }?.id
    LaunchedEffect(state.currentChatId, lastUserMessage) { followsEnd = true }

    // Whatever makes the content longer -- a new message, an answer growing, a chip resolved into
    // its name, a tool call group opened -- shows up as room to scroll further.
    val lastIndex by rememberUpdatedState(state.shownMessages.lastIndex)
    LaunchedEffect(listState, followsEnd) {
        if (!followsEnd) return@LaunchedEffect
        snapshotFlow { listState.canScrollForward }.collect { canScrollForward ->
            if (canScrollForward && lastIndex >= 0) listState.scrollToItem(lastIndex, Int.MAX_VALUE)
        }
    }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        if (state.isEmpty) {
            EmptyChat(
                onAsk = { onEvent(AgentEvent.Ask(it)) },
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = topBarHeight, bottom = bottomHeight),
            )
        } else LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().hazeSource(hazeState),
            // The list runs under the bar and the prompt, and pads itself clear of both.
            contentPadding = PaddingValues(
                start = SCREEN_PADDING,
                end = SCREEN_PADDING,
                top = topBarHeight + 8.dp,
                bottom = bottomHeight,
            ),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            items(state.shownMessages, key = { it.id.toString() }) { message ->
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

        ChatTopBar(
            title = state.currentChat?.name,
            isNewChat = state.currentChatId == null,
            onShowChats = { showChats = true },
            onNewChat = { onEvent(AgentEvent.NewChat) },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .progressiveBackgroundBlur(hazeState, ProgressiveDirection.TopToBottom, colors.background, startRadius = 24.dp)
                .progressiveBackground(colors.background, ProgressiveDirection.TopToBottom)
                // Before the paddings: the list has to clear the status bar and the fade as well.
                .onSizeChanged { topBarHeight = with(density) { it.height.toDp() } }
                .statusBarsPadding()
                .padding(bottom = 20.dp),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // Solid from the prompt down, so nothing shows between it and the nav bar; above
                // it the conversation fades out instead of being cut off.
                .background(Brush.verticalGradient(0f to Color.Transparent, BOTTOM_FADE to colors.background))
                // Before the padding, which holds the nav bar or the keyboard the list has to clear too.
                .onSizeChanged { bottomHeight = with(density) { it.height.toDp() } }
                .padding(start = SCREEN_PADDING, end = SCREEN_PADDING, top = BOTTOM_FADE_HEIGHT, bottom = bottomOffset + PROMPT_GAP),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            state.error?.let { error ->
                Text(
                    text = stringResource(
                        when (error) {
                            AgentError.Load -> Res.string.agent_error_load
                            AgentError.Send -> Res.string.agent_error_send
                            AgentError.Retry -> Res.string.agent_error_retry
                        }
                    ),
                    color = colors.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Composer(
                value = state.input,
                onValueChange = { onEvent(AgentEvent.SetInput(it)) },
                isAnswering = state.isAnswering,
                onSend = { onEvent(AgentEvent.Send) },
                focusRequester = promptFocus,
            )
        }

        // A layer of its own over the fade above the prompt: in the column below it would make
        // that taller when it comes and goes -- the list's padding with it -- and the prompt,
        // drawn after it, would cut off its shadow.
        AnimatedVisibility(
            visible = !followsEnd && state.shownMessages.isNotEmpty(),
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // Zero until the area below is measured, and a negative padding throws.
                .padding(bottom = (bottomHeight - BOTTOM_FADE_HEIGHT + PROMPT_GAP).coerceAtLeast(0.dp)),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(colors.surfaceContainerLow)
                    .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.agent_scroll_to_end)) {
                        followsEnd = true
                        scope.launch { listState.animateScrollToItem(state.shownMessages.lastIndex, Int.MAX_VALUE) }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(PhIcons.Regular.ArrowDown, contentDescription = stringResource(Res.string.agent_scroll_to_end), modifier = Modifier.size(18.dp))
            }
        }
    }

    if (showChats) ChatListSheet(
        chats = state.chats,
        currentChatId = state.currentChatId,
        onSelect = {
            showChats = false
            onEvent(AgentEvent.SelectChat(it))
        },
        onNewChat = {
            showChats = false
            onEvent(AgentEvent.NewChat)
        },
        onDismiss = { showChats = false },
    )
}
