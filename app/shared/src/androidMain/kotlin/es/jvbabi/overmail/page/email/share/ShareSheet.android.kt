@file:OptIn(ExperimentalMaterial3Api::class)

package es.jvbabi.overmail.page.email.share

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Button as FilledButton
import com.phosphor.icons.regular.Copy
import overmail.app.shared.generated.resources.share_list_copy_short
import overmail.app.shared.generated.resources.share_section_access
import overmail.app.shared.generated.resources.share_section_content
import overmail.app.shared.generated.resources.share_section_expiry
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.ArrowLeft
import com.phosphor.icons.regular.CalendarBlank
import com.phosphor.icons.regular.Check
import com.phosphor.icons.regular.DotsThreeVertical
import com.phosphor.icons.regular.Eye
import com.phosphor.icons.regular.EyeSlash
import com.phosphor.icons.regular.LinkSimple
import com.phosphor.icons.regular.LockSimple
import com.phosphor.icons.regular.Paperclip
import com.phosphor.icons.regular.PencilSimple
import com.phosphor.icons.regular.Plus
import com.phosphor.icons.regular.ShareNetwork
import com.phosphor.icons.regular.Tag
import com.phosphor.icons.regular.Trash
import com.phosphor.icons.regular.WarningCircle
import es.jvbabi.overmail.domain.model.Share
import es.jvbabi.overmail.formatDateTime
import es.jvbabi.overmail.shareUrl
import es.jvbabi.overmail.ui.components.Button
import es.jvbabi.overmail.ui.components.ButtonState
import es.jvbabi.overmail.ui.components.Checkbox
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import overmail.app.shared.generated.resources.Res
import overmail.app.shared.generated.resources.share_delete_confirm
import overmail.app.shared.generated.resources.share_delete_deleting
import overmail.app.shared.generated.resources.share_delete_description
import overmail.app.shared.generated.resources.share_delete_failed
import overmail.app.shared.generated.resources.share_delete_title
import overmail.app.shared.generated.resources.share_back
import overmail.app.shared.generated.resources.share_link
import overmail.app.shared.generated.resources.share_new
import overmail.app.shared.generated.resources.share_new_failed
import overmail.app.shared.generated.resources.share_edit_description
import overmail.app.shared.generated.resources.share_edit_title
import overmail.app.shared.generated.resources.share_form_attachments
import overmail.app.shared.generated.resources.share_form_cancel
import overmail.app.shared.generated.resources.share_form_expires_on
import overmail.app.shared.generated.resources.share_form_expiry
import overmail.app.shared.generated.resources.share_form_expiry_1d
import overmail.app.shared.generated.resources.share_form_expiry_30d
import overmail.app.shared.generated.resources.share_form_expiry_7d
import overmail.app.shared.generated.resources.share_form_expiry_custom
import overmail.app.shared.generated.resources.share_form_expiry_missing
import overmail.app.shared.generated.resources.share_form_expiry_never
import overmail.app.shared.generated.resources.share_form_expiry_past
import overmail.app.shared.generated.resources.share_form_failed
import overmail.app.shared.generated.resources.share_form_labels
import overmail.app.shared.generated.resources.share_form_metadata
import overmail.app.shared.generated.resources.share_form_name
import overmail.app.shared.generated.resources.share_form_name_hint
import overmail.app.shared.generated.resources.share_form_name_placeholder
import overmail.app.shared.generated.resources.share_form_ok
import overmail.app.shared.generated.resources.share_form_password
import overmail.app.shared.generated.resources.share_form_password_hide
import overmail.app.shared.generated.resources.share_form_password_hint
import overmail.app.shared.generated.resources.share_form_password_keep_hint
import overmail.app.shared.generated.resources.share_form_password_show
import overmail.app.shared.generated.resources.share_form_password_too_short
import overmail.app.shared.generated.resources.share_form_remove_password
import overmail.app.shared.generated.resources.share_form_save
import overmail.app.shared.generated.resources.share_form_saving
import overmail.app.shared.generated.resources.share_list_attachments
import overmail.app.shared.generated.resources.share_list_copied
import overmail.app.shared.generated.resources.share_list_copy
import overmail.app.shared.generated.resources.share_list_delete
import overmail.app.shared.generated.resources.share_list_edit
import overmail.app.shared.generated.resources.share_list_empty
import overmail.app.shared.generated.resources.share_list_expired
import overmail.app.shared.generated.resources.share_list_expires_at
import overmail.app.shared.generated.resources.share_list_failed
import overmail.app.shared.generated.resources.share_list_labels
import overmail.app.shared.generated.resources.share_list_loading
import overmail.app.shared.generated.resources.share_list_no_expiry
import overmail.app.shared.generated.resources.share_list_password
import overmail.app.shared.generated.resources.share_list_retry
import overmail.app.shared.generated.resources.share_list_share
import overmail.app.shared.generated.resources.share_list_title
import overmail.app.shared.generated.resources.share_list_unnamed
import overmail.app.shared.generated.resources.share_title
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The share sheet on Android: a Material bottom sheet over the mail's page with the links the
 * mail is out under and a button that makes a new one. A share's settings take the sheet over
 * rather than stacking a second one; back goes from there to the list, not out of the sheet.
 */
@Composable
internal actual fun PlatformShareSheet(viewModel: ShareViewModel, visible: Boolean, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(visible) {
        if (visible) sheetState.show() else sheetState.hide()
    }

    // Composed only while it is meant to be seen or still sliding out, see GroupModal.
    if (!visible && !sheetState.isVisible) return

    val state by viewModel.state.collectAsStateWithLifecycle()
    val onEvent = viewModel::onEvent

    // A new link goes straight to the system's share sheet; its settings wait beneath.
    LaunchedEffect(state.linkToShare) {
        val share = state.linkToShare ?: return@LaunchedEffect
        onEvent(ShareEvent.LinkShared)
        shareUrl(viewModel.urlOf(share), share.name)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        BackHandler(enabled = state.editForm != null) { onEvent(ShareEvent.CloseEdit) }

        AnimatedContent(
            targetState = state.editForm != null,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
            label = "share sheet page",
        ) { editing ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(horizontal = 24.dp),
            ) {
                val editForm = state.editForm
                if (editing && editForm != null) SettingsPage(
                    form = editForm,
                    state = state,
                    url = editForm.editing?.let(viewModel::urlOf).orEmpty(),
                    onEvent = onEvent,
                )
                else ListPage(state = state, onEvent = onEvent)

                Spacer(Modifier.height(24.dp))
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }

    state.deleting?.let { share -> DeleteDialog(share = share, deleteState = state.deleteState, onEvent = onEvent) }
}

@Composable
private fun ListPage(state: ShareState, onEvent: (ShareEvent) -> Unit) {
    Text(text = stringResource(Res.string.share_title), style = MaterialTheme.typography.headlineSmall)

    Spacer(Modifier.height(16.dp))

    val creating = state.createState == ShareCreateState.Creating
    when {
        state.shares.isEmpty() && state.listState == ShareListState.Loading -> CenteredStatus {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(Res.string.share_list_loading), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.shares.isEmpty() && state.listState == ShareListState.Failed -> CenteredStatus {
            Icon(PhIcons.Regular.WarningCircle, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(12.dp))
            Text(stringResource(Res.string.share_list_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { onEvent(ShareEvent.Reload) }) { Text(stringResource(Res.string.share_list_retry)) }
        }
        // Nothing out yet: making the first link is all there is to do here.
        state.shares.isEmpty() -> CenteredStatus {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(PhIcons.Regular.LinkSimple, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(Res.string.share_list_empty), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(24.dp))
            FilledButton(onClick = { onEvent(ShareEvent.Create) }, enabled = !creating) {
                ButtonContent(icon = PhIcons.Regular.Plus, text = stringResource(Res.string.share_new), loading = creating)
            }
        }
        else -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(Res.string.share_list_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(onClick = { onEvent(ShareEvent.Create) }, enabled = !creating) {
                    ButtonContent(icon = PhIcons.Regular.Plus, text = stringResource(Res.string.share_new), loading = creating)
                }
            }
            Spacer(Modifier.height(12.dp))
            // Read once per drawing: what counts as expired only has to be right when it is drawn.
            val now = remember(state.shares) { Clock.System.now() }
            Group {
                state.shares.forEachIndexed { index, share ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerLow)
                    ShareRow(
                        share = share,
                        now = now,
                        hasAttachments = state.hasAttachments,
                        copied = state.copiedShareId == share.id,
                        onEvent = onEvent,
                    )
                }
            }
        }
    }

    if (state.createState == ShareCreateState.Failed) Text(
        text = stringResource(Res.string.share_new_failed),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    )
}

@Composable
private fun CenteredStatus(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

/** A label and an icon on a button, or a spinner in place of the icon while it works. */
@Composable
private fun ButtonContent(icon: ImageVector, text: String, loading: Boolean) {
    if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
    else Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(8.dp))
    Text(text)
}

/** Rows that belong together, on one rounded surface. */
@Composable
private fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(content = content)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 24.dp, bottom = 8.dp),
    )
}

/** One share's link and settings: what was just made, or a row of the list that was tapped. */
@Composable
private fun SettingsPage(form: ShareForm, state: ShareState, url: String, onEvent: (ShareEvent) -> Unit) {
    val share = form.editing ?: return

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.offset(x = (-12).dp)) {
        IconButton(enabled = !form.isSaving, onClick = { onEvent(ShareEvent.CloseEdit) }) {
            Icon(PhIcons.Regular.ArrowLeft, contentDescription = stringResource(Res.string.share_back))
        }
        Text(
            text = form.name.trim().ifEmpty { unnamed(share) },
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    Spacer(Modifier.height(16.dp))

    // The link itself, and the two things done with it.
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = url,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val copied = state.copiedShareId == share.id
                FilledButton(onClick = { onEvent(ShareEvent.Copy(share)) }, modifier = Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = copied,
                        transitionSpec = { (scaleIn(initialScale = 0.4f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.4f) + fadeOut()) },
                        label = "copied",
                    ) { isCopied ->
                        Icon(if (isCopied) PhIcons.Regular.Check else PhIcons.Regular.Copy, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (copied) Res.string.share_list_copied else Res.string.share_list_copy_short))
                }
                OutlinedButton(onClick = { onEvent(ShareEvent.ShareLink(share)) }, modifier = Modifier.weight(1f)) {
                    ButtonContent(icon = PhIcons.Regular.ShareNetwork, text = stringResource(Res.string.share_list_share), loading = false)
                }
            }
        }
    }
    Text(
        text = stringResource(Res.string.share_edit_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
    )

    Spacer(Modifier.height(24.dp))

    ShareFormFields(form = form, hasAttachments = state.hasAttachments, onEvent = onEvent)

    Spacer(Modifier.height(24.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            enabled = !form.isSaving,
            onClick = { onEvent(ShareEvent.AskToDelete(share)) },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            ButtonContent(icon = PhIcons.Regular.Trash, text = stringResource(Res.string.share_list_delete), loading = false)
        }
        Spacer(Modifier.weight(1f))
        FilledButton(enabled = form.canSubmit, onClick = { onEvent(ShareEvent.Save) }) {
            if (form.isSaving) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                Spacer(Modifier.width(8.dp))
            }
            Text(stringResource(Res.string.share_form_save))
        }
    }
}

/** What a link without a name is called: the day it was made, which tells it from the others. */
@Composable
private fun unnamed(share: Share): String {
    val languageTag = Locale.current.toLanguageTag()
    return stringResource(Res.string.share_list_unnamed, formatDateTime(share.sharedAt, "dMMM", languageTag))
}

/** The settings of a share, the web app's `ShareForm`, grouped by what they are about. */
@Composable
private fun ShareFormFields(
    form: ShareForm,
    hasAttachments: Boolean,
    onEvent: (ShareEvent) -> Unit,
) {
    OutlinedTextField(
        value = form.name,
        onValueChange = { onEvent(ShareEvent.SetName(it)) },
        label = { Text(stringResource(Res.string.share_form_name)) },
        placeholder = { Text(stringResource(Res.string.share_form_name_placeholder)) },
        supportingText = { Text(stringResource(Res.string.share_form_name_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )

    SectionLabel(stringResource(Res.string.share_section_access))
    var showPassword by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = form.password,
        onValueChange = { onEvent(ShareEvent.SetPassword(it)) },
        label = { Text(stringResource(Res.string.share_form_password)) },
        // An empty field means something else on a link that has a password already.
        supportingText = {
            Text(stringResource(if (form.replacingPassword) Res.string.share_form_password_keep_hint else Res.string.share_form_password_hint))
        },
        singleLine = true,
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onEvent(ShareEvent.Save) }),
        trailingIcon = {
            IconButton(onClick = { showPassword = !showPassword }) {
                Icon(
                    imageVector = if (showPassword) PhIcons.Regular.EyeSlash else PhIcons.Regular.Eye,
                    contentDescription = stringResource(if (showPassword) Res.string.share_form_password_hide else Res.string.share_form_password_show),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    AnimatedVisibility(visible = form.showsRemovePassword || form.showsMetadataOption, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Group(Modifier.padding(top = 8.dp)) {
            AnimatedVisibility(visible = form.showsRemovePassword) {
                SwitchRow(
                    text = stringResource(Res.string.share_form_remove_password),
                    checked = form.removePassword,
                    onCheckedChange = { onEvent(ShareEvent.SetRemovePassword(it)) },
                )
            }
            AnimatedVisibility(visible = form.showsMetadataOption) {
                SwitchRow(
                    text = stringResource(Res.string.share_form_metadata),
                    checked = form.allowMetadataWithoutPassword,
                    onCheckedChange = { onEvent(ShareEvent.SetAllowMetadataWithoutPassword(it)) },
                )
            }
        }
    }

    SectionLabel(stringResource(Res.string.share_section_expiry))
    ExpiryField(form = form, onEvent = onEvent)

    SectionLabel(stringResource(Res.string.share_section_content))
    Group {
        SwitchRow(
            text = stringResource(Res.string.share_form_labels),
            icon = PhIcons.Regular.Tag,
            checked = form.includeLabels,
            onCheckedChange = { onEvent(ShareEvent.SetIncludeLabels(it)) },
        )
        if (hasAttachments) SwitchRow(
            text = stringResource(Res.string.share_form_attachments),
            icon = PhIcons.Regular.Paperclip,
            checked = form.includeAttachments,
            onCheckedChange = { onEvent(ShareEvent.SetIncludeAttachments(it)) },
        )
    }

    FormStatus(form)
}

@Composable
private fun ExpiryField(form: ShareForm, onEvent: (ShareEvent) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = stringResource(form.expiry.label),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(Res.string.share_form_expiry)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ShareExpiry.entries.forEach { expiry ->
                DropdownMenuItem(
                    text = { Text(stringResource(expiry.label)) },
                    onClick = {
                        expanded = false
                        onEvent(ShareEvent.SetExpiry(expiry))
                    },
                )
            }
        }
    }

    AnimatedVisibility(visible = form.expiry == ShareExpiry.Custom, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        var picking by remember { mutableStateOf(false) }
        val languageTag = Locale.current.toLanguageTag()
        val day = form.expiresOn

        Box(Modifier.padding(top = 8.dp)) {
            OutlinedTextField(
                value = day?.let { formatDay(it, languageTag) }.orEmpty(),
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text(stringResource(Res.string.share_form_expires_on)) },
                trailingIcon = { Icon(PhIcons.Regular.CalendarBlank, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
            )
            // A read-only field takes no clicks of its own; this lies over it and does.
            Box(
                Modifier
                    .matchParentSize()
                    .padding(top = 8.dp)
                    .clickable(role = Role.Button) { picking = true }
            )
        }

        if (picking) ExpiryDatePicker(
            initial = day,
            onPicked = { picked ->
                picking = false
                if (picked != null) onEvent(ShareEvent.SetExpiresOn(picked))
            },
        )
    }
}

/** Material's date picker speaks UTC midnights; a day is a day, so it goes over as one. */
@Composable
private fun ExpiryDatePicker(initial: LocalDate?, onPicked: (LocalDate?) -> Unit) {
    val today = remember { Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date }
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initial?.atStartOfDayIn(TimeZone.UTC)?.toEpochMilliseconds(),
        selectableDates = object : SelectableDates {
            // From today on: a link that already ran out is one nobody could open.
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis.toUtcDay() >= today
            override fun isSelectableYear(year: Int): Boolean = year >= today.year
        },
    )

    DatePickerDialog(
        onDismissRequest = { onPicked(null) },
        confirmButton = {
            TextButton(onClick = { onPicked(pickerState.selectedDateMillis?.toUtcDay()) }) {
                Text(stringResource(Res.string.share_form_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = { onPicked(null) }) { Text(stringResource(Res.string.share_form_cancel)) }
        },
    ) {
        DatePicker(state = pickerState)
    }
}

private fun Long.toUtcDay(): LocalDate = Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.UTC).date

private fun formatDay(day: LocalDate, languageTag: String): String =
    formatDateTime(day.atStartOfDayIn(TimeZone.currentSystemDefault()), "yMMMd", languageTag)

private val ShareExpiry.label: StringResource
    get() = when (this) {
        ShareExpiry.OneDay -> Res.string.share_form_expiry_1d
        ShareExpiry.SevenDays -> Res.string.share_form_expiry_7d
        ShareExpiry.ThirtyDays -> Res.string.share_form_expiry_30d
        ShareExpiry.Never -> Res.string.share_form_expiry_never
        ShareExpiry.Custom -> Res.string.share_form_expiry_custom
    }

@Composable
private fun SwitchRow(text: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, icon: ImageVector? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        // The row takes the click as a whole.
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Saving, what went wrong with the last save, or why the form cannot be saved -- in that order. */
@Composable
private fun FormStatus(form: ShareForm) {
    val (text, isError) = when {
        form.saveState == ShareSaveState.Failed -> stringResource(Res.string.share_form_failed) to true
        form.problem == ShareFormProblem.PasswordTooShort -> stringResource(Res.string.share_form_password_too_short, SHARE_MIN_PASSWORD_LENGTH) to true
        form.problem == ShareFormProblem.ExpiryMissing -> stringResource(Res.string.share_form_expiry_missing) to true
        form.problem == ShareFormProblem.ExpiryPast -> stringResource(Res.string.share_form_expiry_past) to true
        else -> return
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 16.dp),
    )
}

@Composable
private fun ShareRow(
    share: Share,
    now: Instant,
    hasAttachments: Boolean,
    copied: Boolean,
    onEvent: (ShareEvent) -> Unit,
) {
    val languageTag = Locale.current.toLanguageTag()
    val validUntil = share.validUntil
    val expired = validUntil != null && validUntil <= now
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEvent(ShareEvent.Edit(share)) }
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (expired) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (share.hasPassword) PhIcons.Regular.LockSimple else PhIcons.Regular.LinkSimple,
                contentDescription = if (share.hasPassword) stringResource(Res.string.share_list_password) else null,
                tint = if (expired) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp),
            )
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = share.name ?: unnamed(share),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = when {
                        validUntil == null -> stringResource(Res.string.share_list_no_expiry)
                        expired -> stringResource(Res.string.share_list_expired)
                        else -> stringResource(Res.string.share_list_expires_at, formatDateTime(validUntil, "dMMMy", languageTag))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (share.includeLabels) SmallIcon(PhIcons.Regular.Tag, stringResource(Res.string.share_list_labels))
                if (hasAttachments && share.includeAttachments) SmallIcon(PhIcons.Regular.Paperclip, stringResource(Res.string.share_list_attachments))
            }
        }

        Row {
            IconButton(onClick = { onEvent(ShareEvent.Copy(share)) }) {
                // The checkmark grows out of the copy icon, like the web app's copy button.
                AnimatedContent(
                    targetState = copied,
                    transitionSpec = { (scaleIn(initialScale = 0.4f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.4f) + fadeOut()) },
                    label = "copied",
                ) { isCopied ->
                    Icon(
                        imageVector = if (isCopied) PhIcons.Regular.Check else PhIcons.Regular.Copy,
                        contentDescription = stringResource(if (isCopied) Res.string.share_list_copied else Res.string.share_list_copy),
                    )
                }
            }
            IconButton(onClick = { onEvent(ShareEvent.ShareLink(share)) }) {
                Icon(PhIcons.Regular.ShareNetwork, contentDescription = stringResource(Res.string.share_list_share))
            }
        }
    }
}

@Composable
private fun SmallIcon(icon: ImageVector, description: String) {
    Icon(icon, contentDescription = description, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * Asks before a link is taken back: it destroys something somebody else may be holding. Stays up
 * with the reason on it when the delete fails.
 */
@Composable
private fun DeleteDialog(share: Share, deleteState: ShareDeleteState, onEvent: (ShareEvent) -> Unit) {
    val deleting = deleteState == ShareDeleteState.Deleting
    AlertDialog(
        onDismissRequest = { onEvent(ShareEvent.CancelDelete) },
        icon = { Icon(PhIcons.Regular.Trash, contentDescription = null) },
        title = { Text(stringResource(Res.string.share_delete_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.share_delete_description, share.name ?: unnamed(share)))
                when (deleteState) {
                    ShareDeleteState.Deleting -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.share_delete_deleting), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ShareDeleteState.Failed -> Text(stringResource(Res.string.share_delete_failed), color = MaterialTheme.colorScheme.error)
                    ShareDeleteState.Idle -> Unit
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !deleting,
                onClick = { onEvent(ShareEvent.ConfirmDelete) },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(Res.string.share_delete_confirm))
            }
        },
        dismissButton = {
            TextButton(enabled = !deleting, onClick = { onEvent(ShareEvent.CancelDelete) }) {
                Text(stringResource(Res.string.share_form_cancel))
            }
        },
    )
}
