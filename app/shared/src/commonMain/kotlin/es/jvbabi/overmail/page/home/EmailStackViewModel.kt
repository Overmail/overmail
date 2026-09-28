@file:OptIn(ExperimentalCoroutinesApi::class)

package es.jvbabi.overmail.page.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.jvbabi.overmail.domain.model.ArchivedState
import es.jvbabi.overmail.domain.model.Email
import es.jvbabi.overmail.domain.model.ViewSorting
import es.jvbabi.overmail.domain.model.ViewSortingKind
import es.jvbabi.overmail.domain.model.ViewState
import es.jvbabi.overmail.domain.repository.AccountRepository
import es.jvbabi.overmail.domain.repository.EmailsRepository
import es.jvbabi.overmail.domain.repository.ViewResult
import es.jvbabi.overmail.page.home.components.stack.StackCardBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * The pile: the inbox, newest first, one mail at a time. It does not follow the
 * view the listing is set to -- the pile is what is left to do, whatever the listing shows.
 */
class EmailStackViewModel(
    accountRepository: AccountRepository,
    private val emailsRepository: EmailsRepository,
) : ViewModel() {
    val state: StateFlow<EmailStackContentState>
        field = MutableStateFlow(EmailStackContentState())

    private val messageChannel = Channel<EmailStackMessage>(Channel.BUFFERED)

    /** What the user is told once, as a snackbar, rather than shown for as long as it holds. */
    val messages: Flow<EmailStackMessage> = messageChannel.receiveAsFlow()

    /**
     * What was swiped off the pile. A kept mail stays in the inbox, so this is all that keeps it
     * from coming straight back; an archived one is held back here too until the database no
     * longer lists it, and while a sync may still bring the server's older state.
     */
    private val handled = MutableStateFlow(emptySet<Uuid>())

    private val bodies = EmailBodies(viewModelScope, emailsRepository::getBody)

    private val account = accountRepository.getAccounts()
        .map { it.firstOrNull() }
        .distinctUntilChanged()

    init {
        viewModelScope.launch {
            account
                .flatMapLatest { account ->
                    if (account == null) flowOf(null to emptyList())
                    else emailsRepository.getView(INBOX, instantLocalEmission = true, user = account)
                        .map { results -> results.filterIsInstance<ViewResult.Item>().map { it.email } }
                        // A write that leaves the inbox as it was -- most of a first sync, which
                        // loads the archive too -- does not deal the pile anew.
                        .distinctUntilChanged()
                        .flowOn(Dispatchers.Default)
                        .map { emails -> account to emails }
                }
                .combine(handled) { (account, emails), handled -> account to emails.filter { it.id !in handled } }
                .collect { (account, emails) ->
                    state.update { it.copy(emails = emails, isLoading = false) }
                    // One that failed is asked for again, the next time the pile changes with it
                    // still near the top.
                    if (account != null) emails.take(BODIES_AHEAD).forEach { bodies.load(it.id, account) }
                }
        }
        viewModelScope.launch {
            bodies.bodies.collect { known -> state.update { it.copy(bodies = known) } }
        }
    }

    fun onEvent(event: EmailStackEvent) {
        when (event) {
            is EmailStackEvent.Archive -> archive(event.email)
            is EmailStackEvent.Keep -> {
                handled.update { it + event.email.id }
                markRead(event.email)
            }
            is EmailStackEvent.Read -> markRead(event.email)
        }
    }

    /** Quietly: a failed request puts the mail back to unread, which is all the user needs to see. */
    private fun markRead(email: Email) {
        if (email.isRead) return
        viewModelScope.launch { emailsRepository.setRead(email, isRead = true, email.overmailAccount) }
    }

    /** Off the pile at once; the request runs on its own, and a failed one puts the mail back. */
    private fun archive(email: Email) {
        handled.update { it + email.id }
        markRead(email)
        viewModelScope.launch {
            emailsRepository.setArchivedState(email, ArchivedState.Archive, email.overmailAccount).onFailure {
                handled.update { it - email.id }
                messageChannel.send(EmailStackMessage.ArchiveFailed)
            }
        }
    }
}

/** How many mails from the top of the pile have their body fetched ahead: the cards that show, and the next. */
private const val BODIES_AHEAD = 5

/** The mailbox without its groups: the pile is one run of mails, not a listing. */
private val INBOX = ViewState(
    filter = ViewState.Mailbox.filter,
    sorting = ViewSorting(ViewSortingKind.Date),
)

data class EmailStackContentState(
    /** Newest first: the first one is the card on top. */
    val emails: List<Email> = emptyList(),
    /** What the mails near the top of the pile say, as far as it is asked for. */
    val bodies: Map<Uuid, StackCardBody> = emptyMap(),
    val isLoading: Boolean = true,
)

enum class EmailStackMessage {
    /** Archiving a mail did not reach the server; it is back on the pile. */
    ArchiveFailed,
}

sealed class EmailStackEvent {
    /** Swiped to the left. */
    data class Archive(val email: Email) : EmailStackEvent()

    /** Swiped to the right: stays in the inbox, but is off the pile. */
    data class Keep(val email: Email) : EmailStackEvent()

    /** Lay on top of the pile long enough to count as seen. */
    data class Read(val email: Email) : EmailStackEvent()
}
