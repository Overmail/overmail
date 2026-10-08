package es.jvbabi.overmail.page.email.components.attachments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.Paperclip
import es.jvbabi.overmail.domain.model.Attachment
import es.jvbabi.overmail.ui.theme.AppTheme
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import overmail.app.shared.generated.resources.Res
import overmail.app.shared.generated.resources.email_attachments_title
import kotlin.uuid.Uuid

/**
 * The attachments of a mail under a heading, the web app's `AttachmentList`. A tap downloads one
 * into the app's cache and opens it, see [AttachmentsViewModel].
 */
@Composable
fun AttachmentList(
    attachments: List<Attachment>,
    modifier: Modifier = Modifier,
) {
    val viewModel = koinViewModel<AttachmentsViewModel>()
    val states by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(attachments) { viewModel.onAttachmentsShown(attachments) }

    AttachmentList(
        attachments = attachments,
        states = states,
        onClick = viewModel::onClick,
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttachmentList(
    attachments: List<Attachment>,
    states: Map<Uuid, AttachmentState>,
    onClick: (Attachment) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.semantics { heading() },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = PhIcons.Regular.Paperclip,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = stringResource(Res.string.email_attachments_title),
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, lineHeight = 20.sp),
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            attachments.forEach { attachment ->
                AttachmentItem(
                    attachment = attachment,
                    state = states[attachment.id] ?: AttachmentState.Remote,
                    onClick = { onClick(attachment) },
                )
            }
        }
    }
}

@Composable
private fun AttachmentListContent() {
    Surface {
        AttachmentList(
            attachments = previewAttachments,
            states = mapOf(
                previewAttachments[0].id to AttachmentState.Cached,
                previewAttachments[1].id to AttachmentState.Downloading(0.65f),
                previewAttachments[3].id to AttachmentState.Failed(AttachmentFailure.Download),
            ),
            onClick = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview
@Composable
private fun AttachmentListPreview() {
    AppTheme(dynamicColor = false) { AttachmentListContent() }
}

@Preview
@Composable
private fun AttachmentListDarkPreview() {
    AppTheme(darkTheme = true, dynamicColor = false) { AttachmentListContent() }
}
