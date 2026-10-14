package es.jvbabi.overmail.page.agent.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.List
import com.phosphor.icons.regular.NotePencil
import es.jvbabi.overmail.domain.model.AiChat
import es.jvbabi.overmail.page.agent.SCREEN_PADDING
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import overmail.app.shared.generated.resources.Res
import overmail.app.shared.generated.resources.agent_chats
import overmail.app.shared.generated.resources.agent_new_chat
import overmail.app.shared.generated.resources.agent_title_new
import overmail.app.shared.generated.resources.agent_title_unnamed
import kotlin.uuid.Uuid

/** The chats on the left, the open one's name in the middle, a new one on the right. */
@Composable
fun ChatTopBar(
    title: String?,
    isNewChat: Boolean,
    onShowChats: () -> Unit,
    onNewChat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        // The icons are smaller than the buttons around them: the button sits off the margin by the
        // difference, so the icon itself is on it.
        modifier = modifier.fillMaxWidth().padding(horizontal = SCREEN_PADDING - (BUTTON_SIZE - ICON_SIZE) / 2, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarButton(PhIcons.Regular.List, Res.string.agent_chats, onShowChats)
        AnimatedContent(
            targetState = when {
                isNewChat -> null
                else -> title ?: ""
            },
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(150)) },
            modifier = Modifier.weight(1f),
        ) { shown ->
            Text(
                text = shown?.ifEmpty { stringResource(Res.string.agent_title_unnamed) }
                    ?: stringResource(Res.string.agent_title_new),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        BarButton(PhIcons.Regular.NotePencil, Res.string.agent_new_chat, onNewChat, enabled = !isNewChat)
    }
}

@Composable
private fun BarButton(icon: ImageVector, label: StringResource, onClick: () -> Unit, enabled: Boolean = true) {
    val color = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .size(BUTTON_SIZE)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = stringResource(label), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(label),
            tint = if (enabled) color else color.copy(alpha = 0.3f),
            modifier = Modifier.size(ICON_SIZE),
        )
    }
}

private val BUTTON_SIZE = 44.dp
private val ICON_SIZE = 22.dp

/** Every chat, newest first, the open one marked; and a new one at the top. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListSheet(
    chats: List<AiChat>,
    currentChatId: Uuid?,
    onSelect: (Uuid) -> Unit,
    onNewChat: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.agent_chats),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            BarButton(PhIcons.Regular.NotePencil, Res.string.agent_new_chat, onNewChat)
        }
        // Owns the bottom inset itself: the last chats scroll up from under the gesture bar.
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            items(chats, key = { it.id.toString() }) { chat ->
                val isCurrent = chat.id == currentChatId
                Text(
                    text = chat.name ?: stringResource(Res.string.agent_title_unnamed),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .then(if (isCurrent) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest) else Modifier)
                        .clickable { onSelect(chat.id) }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
            item { Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 8.dp)) }
        }
    }
}
