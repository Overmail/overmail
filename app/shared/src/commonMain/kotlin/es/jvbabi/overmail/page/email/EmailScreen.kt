package es.jvbabi.overmail.page.email

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.Archive
import com.phosphor.icons.regular.ArrowLeft
import com.phosphor.icons.regular.EnvelopeSimple
import com.phosphor.icons.regular.EnvelopeSimpleOpen
import com.phosphor.icons.regular.Prohibit
import com.phosphor.icons.regular.ShareNetwork
import com.phosphor.icons.regular.TrayArrowDown
import es.jvbabi.overmail.domain.model.ArchivedState
import es.jvbabi.overmail.domain.model.Email
import es.jvbabi.overmail.page.email.components.EmailParticipants
import es.jvbabi.overmail.page.home.PREVIEW_ITEMS
import es.jvbabi.overmail.page.home.components.stack.StackCardBody
import es.jvbabi.overmail.shareUrl
import es.jvbabi.overmail.ui.components.EmailHtmlBody
import es.jvbabi.overmail.ui.components.LabelBadge
import es.jvbabi.overmail.ui.theme.AppTheme
import es.jvbabi.overmail.ui.theme.displayFontFamily
import es.jvbabi.overmail.ui.transition.isScreenSettled
import es.jvbabi.overmail.ui.transition.mailPage
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import overmail.app.shared.generated.resources.Res
import overmail.app.shared.generated.resources.email_action_failed
import overmail.app.shared.generated.resources.email_archive
import overmail.app.shared.generated.resources.email_back
import overmail.app.shared.generated.resources.email_body_empty
import overmail.app.shared.generated.resources.email_body_failed
import overmail.app.shared.generated.resources.email_mark_read
import overmail.app.shared.generated.resources.email_mark_spam
import overmail.app.shared.generated.resources.email_mark_unread
import overmail.app.shared.generated.resources.email_no_subject
import overmail.app.shared.generated.resources.email_share_link
import overmail.app.shared.generated.resources.email_unarchive
import org.jetbrains.compose.resources.StringResource
import kotlin.uuid.Uuid

/**
 * The mail of [emailId] on a page of its own, what the web app's mail page is: its subject and
 * what can be done with it, who it is between, its labels and what it says. It grows out of
 * whatever showed the mail and was tapped, see [mailPage], and can be opened from anywhere
 * else as well -- a mail this device does not hold yet is loaded.
 */
@Composable
fun EmailScreen(emailId: Uuid, onBack: () -> Unit) {
    val viewModel = koinViewModel<EmailViewModel> { parametersOf(emailId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            when (message) {
                EmailMessage.ActionFailed -> snackbarHostState.showSnackbar(getString(Res.string.email_action_failed))
            }
        }
    }

    EmailContent(
        emailId = emailId,
        state = state,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onEvent = viewModel::onEvent,
    )
}

@Composable
private fun EmailContent(
    emailId: Uuid,
    state: EmailState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onEvent: (EmailEvent) -> Unit,
) {
    // A background of its own, drawn by mailPage: it grows out of the row over the screen below.
    Box(
        Modifier
            .fillMaxSize()
            .mailPage(
                emailId = emailId,
                background = MaterialTheme.colorScheme.background,
                cardColor = MaterialTheme.colorScheme.surfaceContainer,
            )
    ) {
        Column(Modifier.fillMaxSize()) {
            EmailTopBar(email = state.email, onBack = onBack, onEvent = onEvent)

            val email = state.email
            if (email == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(
                    text = email.subject ?: stringResource(Res.string.email_no_subject),
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = displayFontFamily(),
                    color = if (email.subject == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )

                EmailParticipants(email = email, modifier = Modifier.padding(horizontal = 24.dp))

                if (email.labels.isNotEmpty()) FlowRow(
                    modifier = Modifier.padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    email.labels.forEach { label -> LabelBadge(name = label.name, color = label.color) }
                }

                EmailBody(body = state.body)

                // The page runs on under the system bar, and ends above it.
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars).padding(bottom = 16.dp))
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        )
    }
}

/** The way back, and what can be done with the mail -- once it is here. */
@Composable
private fun EmailTopBar(email: Email?, onBack: () -> Unit, onEvent: (EmailEvent) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionButton(PhIcons.Regular.ArrowLeft, Res.string.email_back, onBack)
        Spacer(Modifier.weight(1f))
        if (email == null) return@Row

        if (email.isRead) ActionButton(PhIcons.Regular.EnvelopeSimple, Res.string.email_mark_unread) { onEvent(EmailEvent.SetRead(false)) }
        else ActionButton(PhIcons.Regular.EnvelopeSimpleOpen, Res.string.email_mark_read) { onEvent(EmailEvent.SetRead(true)) }

        // Anything but the mailbox is filed -- archived or spam -- and can only go back.
        if (email.archivedState == ArchivedState.Unarchive) {
            ActionButton(PhIcons.Regular.Archive, Res.string.email_archive) { onEvent(EmailEvent.SetArchivedState(ArchivedState.Archive)) }
            ActionButton(PhIcons.Regular.Prohibit, Res.string.email_mark_spam) { onEvent(EmailEvent.SetArchivedState(ArchivedState.Spam)) }
        }
        else ActionButton(PhIcons.Regular.TrayArrowDown, Res.string.email_unarchive) { onEvent(EmailEvent.SetArchivedState(ArchivedState.Unarchive)) }

        ActionButton(PhIcons.Regular.ShareNetwork, Res.string.email_share_link) {
            shareUrl(emailWebUrl(email), email.subject)
        }
    }
}

@Composable
private fun ActionButton(icon: ImageVector, description: StringResource, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(imageVector = icon, contentDescription = stringResource(description))
    }
}

/**
 * What the mail says. An html mail is live only once the page has arrived: a web view does not
 * move with the page on its way in or out, so it is its picture until then.
 */
@Composable
private fun EmailBody(body: StackCardBody) {
    when (body) {
        StackCardBody.Loading -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is StackCardBody.Html -> EmailHtmlBody(
            html = body.html,
            picture = body.picture,
            isLive = isScreenSettled(),
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        is StackCardBody.Text -> Text(
            text = body.text.ifBlank { stringResource(Res.string.email_body_empty) },
            style = MaterialTheme.typography.bodyMedium,
            color = if (body.text.isBlank()) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        StackCardBody.Failed -> Text(
            text = stringResource(Res.string.email_body_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

@Composable
@Preview
private fun EmailContentPreview() {
    AppTheme(dynamicColor = false) {
        val email = PREVIEW_ITEMS.first().email
        EmailContent(
            emailId = email.id,
            state = EmailState(email = email, body = StackCardBody.Text("Hi,\n\nthe numbers for Q2 are in. See you on Monday.")),
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {},
            onEvent = {},
        )
    }
}
