package es.jvbabi.overmail.page.email.components.attachments

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.Download
import com.phosphor.icons.regular.X
import es.jvbabi.overmail.domain.model.Attachment
import es.jvbabi.overmail.ui.theme.AppTheme
import nl.jacobras.humanreadable.HumanReadable
import org.jetbrains.compose.resources.stringResource
import overmail.app.shared.generated.resources.Res
import overmail.app.shared.generated.resources.email_attachments_cancel
import overmail.app.shared.generated.resources.email_attachments_download
import overmail.app.shared.generated.resources.email_attachments_progress
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

private val ItemShape = RoundedCornerShape(8.dp)

/**
 * One attachment, the web app's `Attachment`: its type, name and size, and a tap that downloads it
 * or cancels the download that is running.
 *
 * While downloading the type gives way to a ring of [downloadProgress]. Unlike the web, the ring
 * holds the cancel icon the whole time rather than only on hover: a phone has no hover, and the
 * tap cancels either way.
 *
 * @param downloadProgress how far the download is, from 0 to 1; null while none is running.
 */
@Composable
fun AttachmentItem(
    attachment: Attachment,
    downloadProgress: Float?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f)

    val downloading = downloadProgress != null
    val showFiletypeIcon = !downloading && !hovered

    val size = HumanReadable.fileSize(attachment.size)
    val label = stringResource(
        if (downloading) Res.string.email_attachments_cancel else Res.string.email_attachments_download,
        attachment.filename,
    )

    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(ItemShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, ItemShape)
            .background(if (hovered) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface)
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                onClick { onClick(); true }
            }
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp)) {
            // The type slides down and out, the download icon down and in from above.
            val filetypeOffset by animateDpAsState(if (showFiletypeIcon) 0.dp else 16.dp)
            val filetypeAlpha by animateFloatAsState(if (showFiletypeIcon) 1f else 0f)
            val actionOffset by animateDpAsState(if (showFiletypeIcon) (-16).dp else 0.dp)

            AttachmentIcon(
                filename = attachment.filename,
                contentType = attachment.contentType,
                modifier = Modifier
                    .offset(y = filetypeOffset)
                    .alpha(filetypeAlpha),
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(y = actionOffset)
                    .alpha(1f - filetypeAlpha),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (downloading) PhIcons.Regular.X else PhIcons.Regular.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(16.dp),
                )
                if (downloadProgress != null) ProgressRing(downloadProgress)
            }
        }

        Column(Modifier.weight(1f, fill = false)) {
            Text(
                text = attachment.filename,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            Text(
                text = if (downloadProgress != null) stringResource(
                    Res.string.email_attachments_progress,
                    size,
                    (downloadProgress.coerceIn(0f, 1f) * 100).roundToInt(),
                ) else size,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontFeatureSettings = "tnum",
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** The web's svg ring: a 2dp track at radius 14 of 32, filled clockwise from the top. */
@Composable
private fun ProgressRing(progress: Float) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), animationSpec = tween(200))
    val track = MaterialTheme.colorScheme.outlineVariant
    val fill = MaterialTheme.colorScheme.primary

    Canvas(Modifier.fillMaxSize()) {
        val stroke = 2.dp.toPx()
        val radius = 14.dp.toPx()
        val topLeft = Offset(center.x - radius, center.y - radius)
        val arcSize = Size(radius * 2, radius * 2)

        drawCircle(color = track, radius = radius, style = Stroke(stroke))
        drawArc(
            color = fill,
            startAngle = -90f,
            sweepAngle = 360f * animated,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

private val previewEmailId = Uuid.random()

internal val previewAttachments = listOf(
    Attachment(Uuid.random(), previewEmailId, "Rechnung_2026-09.pdf", "application/pdf", 184_320),
    Attachment(Uuid.random(), previewEmailId, "Urlaubsfotos.zip", "application/zip", 48_234_496),
    Attachment(Uuid.random(), previewEmailId, "IMG_4021.HEIC", "image/heic", 2_734_080),
    Attachment(Uuid.random(), previewEmailId, "Termin.ics", "text/calendar", 1_204),
    Attachment(Uuid.random(), previewEmailId, "signature.asc", "application/pgp-signature", 833),
    Attachment(Uuid.random(), previewEmailId, "unbenannt", "application/octet-stream", 12_000),
    Attachment(
        Uuid.random(),
        previewEmailId,
        "Protokoll der Mitgliederversammlung vom 14. September 2026 (endgültige Fassung).docx",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        96_512,
    ),
)

@Composable
private fun AttachmentItemStates() {
    Surface {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AttachmentItem(previewAttachments[0], downloadProgress = null, onClick = {})
            AttachmentItem(previewAttachments[1], downloadProgress = 0f, onClick = {})
            AttachmentItem(previewAttachments[1], downloadProgress = 0.42f, onClick = {})
            AttachmentItem(previewAttachments[1], downloadProgress = 1f, onClick = {})
            AttachmentItem(previewAttachments.last(), downloadProgress = null, onClick = {})
        }
    }
}

@Preview
@Composable
private fun AttachmentItemPreview() {
    AppTheme(dynamicColor = false) { AttachmentItemStates() }
}

@Preview
@Composable
private fun AttachmentItemDarkPreview() {
    AppTheme(darkTheme = true, dynamicColor = false) { AttachmentItemStates() }
}
