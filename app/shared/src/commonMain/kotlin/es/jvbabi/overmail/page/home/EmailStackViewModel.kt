@file:OptIn(ExperimentalCoroutinesApi::class)

package es.jvbabi.overmail.page.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.jvbabi.overmail.domain.model.Email
import es.jvbabi.overmail.domain.model.ViewSorting
import es.jvbabi.overmail.domain.model.ViewSortingKind
import es.jvbabi.overmail.domain.model.ViewState
import es.jvbabi.overmail.domain.repository.AccountRepository
import es.jvbabi.overmail.domain.repository.EmailsRepository
import es.jvbabi.overmail.domain.repository.ViewResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * The pile above the listing: the inbox, newest first, one mail at a time. It does not follow the
 * view the listing is set to -- the pile is what is left to do, whatever the listing shows.
 */
class EmailStackViewModel(
    accountRepository: AccountRepository,
    private val emailsRepository: EmailsRepository,
) : ViewModel() {
    val state: StateFlow<EmailStackContentState>
        field = MutableStateFlow(EmailStackContentState())

    /**
     * What was swiped off the pile. Nothing reaches the server yet, so this is all that keeps a
     * handled mail from coming straight back.
     */
    private val handled = MutableStateFlow(emptySet<Uuid>())

    private val account = accountRepository.getAccounts()
        .map { it.firstOrNull() }
        .distinctUntilChanged()

    init {
        viewModelScope.launch {
            account
                .flatMapLatest { account ->
                    if (account == null) flowOf(emptyList())
                    else emailsRepository.getView(INBOX, instantLocalEmission = true, user = account)
                        .map { results -> results.filterIsInstance<ViewResult.Item>().map { it.email } }
                }
                .combine(handled) { emails, handled -> emails.filter { it.id !in handled } }
                .collect { emails -> state.value = EmailStackContentState(emails = emails, isLoading = false) }
        }
    }

    fun onEvent(event: EmailStackEvent) {
        when (event) {
            is EmailStackEvent.Archive -> handled.update { it + event.email.id }
            is EmailStackEvent.Keep -> handled.update { it + event.email.id }
        }
    }
}

/** The mailbox without its groups: the pile is one run of mails, not a listing. */
private val INBOX = ViewState(
    filter = ViewState.Mailbox.filter,
    sorting = ViewSorting(ViewSortingKind.Date),
)

data class EmailStackContentState(
    /** Newest first: the first one is the card on top. */
    val emails: List<Email> = emptyList(),
    val isLoading: Boolean = true,
)

sealed class EmailStackEvent {
    /** Swiped to the left. */
    data class Archive(val email: Email) : EmailStackEvent()

    /** Swiped to the right: stays in the inbox, but is off the pile. */
    data class Keep(val email: Email) : EmailStackEvent()
}
