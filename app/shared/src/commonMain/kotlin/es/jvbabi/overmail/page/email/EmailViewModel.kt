@file:OptIn(ExperimentalCoroutinesApi::class)

package es.jvbabi.overmail.page.email

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.jvbabi.overmail.domain.model.ArchivedState
import es.jvbabi.overmail.domain.model.Email
import es.jvbabi.overmail.domain.repository.AccountRepository
import es.jvbabi.overmail.domain.repository.EmailsRepository
import es.jvbabi.overmail.page.home.EmailBodies
import es.jvbabi.overmail.page.home.components.stack.StackCardBody
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * One mail on a page of its own, [emailId]: kept current while the page is open, and read as soon
 * as it is opened, wherever from.
 */
class EmailViewModel(
    private val emailId: Uuid,
    accountRepository: AccountRepository,
    private val emailsRepository: EmailsRepository,
) : ViewModel() {
    // What is known of the mail already, so the page shows it from its first frame on.
    val state: StateFlow<EmailState>
        field = MutableStateFlow(EmailState(email = emailsRepository.peekEmail(emailId), body = peekBody()))

    private val messageChannel = Channel<EmailMessage>(Channel.BUFFERED)

    /** What the user is told once, as a snackbar. */
    val messages: Flow<EmailMessage> = messageChannel.receiveAsFlow()

    // There is no account switcher yet, so the mail is looked for in the first account.
    private val account = accountRepository.getAccounts()
        .map { it.firstOrNull() }
        .distinctUntilChanged()

    private val emailBodies = EmailBodies(viewModelScope, emailsRepository::getPicture, emailsRepository::getBody)

    /** Whether opening the page has marked the mail read already; only the first sight of it does. */
    private var hasMarkedRead = false

    init {
        state.value.body.takeIf { it != StackCardBody.Loading }?.let { emailBodies.seed(emailId, it) }
        // The body is on its way while the page is.
        state.value.email?.let(::onEmailLoaded)
        viewModelScope.launch {
            account
                .flatMapLatest { account ->
                    if (account == null) flowOf(null)
                    else emailsRepository.getEmail(emailId, account)
                }
                .combine(emailBodies.bodies) { email, bodies -> email to bodies[emailId] }
                .collect { (email, body) ->
                    state.value = EmailState(email = email, body = body ?: StackCardBody.Loading)
                    if (email != null) onEmailLoaded(email)
                }
        }
    }

    /** What is known of what the mail says without waiting, see [EmailsRepository.peekBody]. */
    private fun peekBody(): StackCardBody {
        val body = emailsRepository.peekBody(emailId) ?: return StackCardBody.Loading
        val html = body.html ?: return StackCardBody.Text(body.text.orEmpty())
        return StackCardBody.Html(html, emailsRepository.peekPicture(emailId))
    }

    private fun onEmailLoaded(email: Email) {
        // The picture is only what is shown on the way in and out, so it goes before any card of the pile.
        emailBodies.load(email.id, email.overmailAccount, order = { PAGE_RENDER_ORDER })
        if (hasMarkedRead) return
        hasMarkedRead = true
        if (!email.isRead) setRead(email, true)
    }

    fun onEvent(event: EmailEvent) {
        val email = state.value.email ?: return
        when (event) {
            is EmailEvent.SetRead -> setRead(email, event.isRead)
            is EmailEvent.SetArchivedState -> viewModelScope.launch {
                emailsRepository.setArchivedState(email, event.archivedState, email.overmailAccount)
                    .onFailure { messageChannel.send(EmailMessage.ActionFailed) }
            }
        }
    }

    private fun setRead(email: Email, isRead: Boolean) {
        viewModelScope.launch {
            emailsRepository.setRead(email, isRead, email.overmailAccount)
                .onFailure { messageChannel.send(EmailMessage.ActionFailed) }
        }
    }
}

data class EmailState(
    /** Null while it is on its way -- from the server, for one this device has not seen yet. */
    val email: Email? = null,
    val body: StackCardBody = StackCardBody.Loading,
)

/** Where the page's picture of its mail goes in line: before any card of the pile. */
private const val PAGE_RENDER_ORDER = -1

sealed class EmailEvent {
    data class SetRead(val isRead: Boolean) : EmailEvent()
    data class SetArchivedState(val archivedState: ArchivedState) : EmailEvent()
}

sealed class EmailMessage {
    /** A change to the mail did not reach the server and was taken back. */
    data object ActionFailed : EmailMessage()
}
