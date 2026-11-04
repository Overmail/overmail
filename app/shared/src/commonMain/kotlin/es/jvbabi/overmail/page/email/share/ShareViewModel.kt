package es.jvbabi.overmail.page.email.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.jvbabi.overmail.domain.model.Email
import es.jvbabi.overmail.domain.model.Share
import es.jvbabi.overmail.domain.repository.SharesRepository
import es.jvbabi.overmail.page.email.shareWebUrl
import es.jvbabi.overmail.setClipboardText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** How long the copy button stays a checkmark: a receipt for what just happened, nothing more. */
private val COPIED_FLASH = 2.seconds

/**
 * The share sheet of one mail, behind the Compose sheet on Android and the SwiftUI one on iOS:
 * the links the mail is already out under, and what can be done to one of them -- shared,
 * copied, edited, taken back. What the web app's share dialog does, put the way a phone shares.
 *
 * A new link is made in one tap with the defaults of [ShareForm] -- a week, labels and
 * attachments, no password -- and handed straight to the platform's share sheet
 * ([ShareState.linkToShare]); its settings open beneath, for whoever wants to change them. Most
 * links never need to: the link stays the same whatever is changed later.
 *
 * Kept as long as the mail's page, and made with it: the list is read as soon as the page opens,
 * so the sheet has it from its first frame. The sheet calls [onEvent] with [ShareEvent.Open] each
 * time it comes up, which reads it again behind what is already shown.
 */
class ShareViewModel(
    private val email: Email,
    private val sharesRepository: SharesRepository,
    private val clock: Clock = Clock.System,
    private val timeZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val copyToClipboard: (String) -> Unit = ::setClipboardText,
) : ViewModel() {
    val state: StateFlow<ShareState>
        field = MutableStateFlow(ShareState(hasAttachments = email.attachments.isNotEmpty()))

    private var copiedReset: Job? = null
    private var loadJob: Job? = null

    init {
        load()
    }

    fun onEvent(event: ShareEvent) {
        when (event) {
            ShareEvent.Open -> {
                state.update { it.copy(editForm = null, deleting = null, linkToShare = null, createState = ShareCreateState.Idle) }
                load()
            }
            ShareEvent.Reload -> load()
            ShareEvent.Create -> create()
            ShareEvent.LinkShared -> state.update { it.copy(linkToShare = null) }
            is ShareEvent.ShareLink -> state.update { it.copy(linkToShare = event.share) }

            is ShareEvent.SetName -> updateForm { copy(name = event.name) }
            is ShareEvent.SetPassword -> updateForm { copy(password = event.password) }
            is ShareEvent.SetRemovePassword -> updateForm { copy(removePassword = event.remove) }
            is ShareEvent.SetAllowMetadataWithoutPassword -> updateForm { copy(allowMetadataWithoutPassword = event.allow) }
            is ShareEvent.SetIncludeLabels -> updateForm { copy(includeLabels = event.include) }
            is ShareEvent.SetIncludeAttachments -> updateForm { copy(includeAttachments = event.include) }
            is ShareEvent.SetExpiry -> updateForm {
                // Picking a date starts at the one the preset would have ended on, not at nothing.
                val start = expiresOn ?: validUntil(clock.now(), timeZone())?.let { localDate(it) }
                copy(expiry = event.expiry, expiresOn = if (event.expiry == ShareExpiry.Custom) start else expiresOn)
            }
            is ShareEvent.SetExpiresOn -> updateForm { copy(expiresOn = event.date) }
            ShareEvent.Save -> save()

            is ShareEvent.Copy -> copy(event.share)
            is ShareEvent.Edit -> state.update { it.copy(editForm = ShareForm.of(event.share, timeZone()).validated()) }
            ShareEvent.CloseEdit -> state.update { if (it.editForm?.isSaving == true) it else it.copy(editForm = null) }
            is ShareEvent.AskToDelete -> state.update { it.copy(deleting = event.share, deleteState = ShareDeleteState.Idle) }
            ShareEvent.CancelDelete -> state.update { if (it.deleteState == ShareDeleteState.Deleting) it else it.copy(deleting = null) }
            ShareEvent.ConfirmDelete -> confirmDelete()
        }
    }

    /** The link of [share], as it is copied and shared. */
    fun urlOf(share: Share): String = shareWebUrl(email.overmailAccount.homeserver, share, email.subject)

    private fun ShareForm.validated() = validated(clock.now(), timeZone())

    private fun localDate(instant: Instant): LocalDate = instant.toLocalDateTime(timeZone()).date

    private fun updateForm(change: ShareForm.() -> ShareForm) = state.update { current ->
        current.editForm?.let { current.copy(editForm = it.change().validated()) } ?: current
    }

    private fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            // Loading is only shown before the first answer; after it, a reload happens behind the
            // list as it stands -- an empty one included.
            state.update { it.copy(listState = if (it.listState == ShareListState.Ready) it.listState else ShareListState.Loading) }
            sharesRepository.list(email.id, email.overmailAccount)
                .onSuccess { shares -> state.update { it.copy(shares = shares, listState = ShareListState.Ready) } }
                .onFailure { state.update { it.copy(listState = ShareListState.Failed) } }
        }
    }

    private fun create() {
        if (state.value.createState == ShareCreateState.Creating) return

        val draft = ShareForm().toDraft(clock.now(), timeZone())
        state.update { it.copy(createState = ShareCreateState.Creating) }
        viewModelScope.launch {
            sharesRepository.create(email.id, draft, email.overmailAccount)
                .onSuccess { share ->
                    state.update {
                        it.copy(
                            createState = ShareCreateState.Idle,
                            // At the top at once, where the reload below puts it as well.
                            shares = listOf(share) + it.shares.filterNot { other -> other.id == share.id },
                            listState = ShareListState.Ready,
                            // The settings beneath the share sheet, for whoever wants to change them.
                            editForm = ShareForm.of(share, timeZone()).validated(),
                            linkToShare = share,
                        )
                    }
                    load()
                }
                .onFailure { state.update { it.copy(createState = ShareCreateState.Failed) } }
        }
    }

    private fun save() {
        val form = state.value.editForm?.validated() ?: return
        val editing = form.editing ?: return
        if (!form.canSubmit) return

        val draft = form.toDraft(clock.now(), timeZone())
        updateForm { copy(saveState = ShareSaveState.Saving) }
        viewModelScope.launch {
            sharesRepository.update(email.id, editing.id, draft, email.overmailAccount)
                .onSuccess {
                    state.update { it.copy(editForm = null) }
                    load()
                }
                .onFailure { updateForm { copy(saveState = ShareSaveState.Failed) } }
        }
    }

    private fun copy(share: Share) {
        copyToClipboard(urlOf(share))
        state.update { it.copy(copiedShareId = share.id) }
        copiedReset?.cancel()
        copiedReset = viewModelScope.launch {
            delay(COPIED_FLASH)
            state.update { it.copy(copiedShareId = null) }
        }
    }

    private fun confirmDelete() {
        val target = state.value.deleting ?: return
        if (state.value.deleteState == ShareDeleteState.Deleting) return

        state.update { it.copy(deleteState = ShareDeleteState.Deleting) }
        viewModelScope.launch {
            sharesRepository.delete(email.id, target.id, email.overmailAccount)
                .onSuccess {
                    state.update {
                        it.copy(
                            deleting = null,
                            deleteState = ShareDeleteState.Idle,
                            editForm = it.editForm?.takeUnless { form -> form.editing?.id == target.id },
                            copiedShareId = it.copiedShareId?.takeUnless { id -> id == target.id },
                            // Gone from the list at once; the reload below says what else changed.
                            shares = it.shares.filterNot { share -> share.id == target.id },
                        )
                    }
                    load()
                }
                // The link is still there, so the confirmation stays open with the reason on it.
                .onFailure { state.update { it.copy(deleteState = ShareDeleteState.Failed) } }
        }
    }
}

enum class ShareListState {
    Loading,
    Ready,
    Failed,
}

enum class ShareCreateState {
    Idle,
    Creating,
    /** The last try did not go through; the button says so and can be tapped again. */
    Failed,
}

enum class ShareDeleteState {
    Idle,
    Deleting,
    Failed,
}

data class ShareState(
    val listState: ShareListState = ShareListState.Loading,
    /** Newest first, expired ones included: this is the only place they can be taken back. */
    val shares: List<Share> = emptyList(),
    /** Whether the mail has attachments; without any, there is nothing to share them for. */
    val hasAttachments: Boolean,
    val createState: ShareCreateState = ShareCreateState.Idle,
    /** The settings of one share, open on top of the list; null while the list is all there is. */
    val editForm: ShareForm? = null,
    /**
     * A link the platform's share sheet is to be opened with. The sheet that shows it answers
     * with [ShareEvent.LinkShared], so it opens once and not on every redraw.
     */
    val linkToShare: Share? = null,
    /** The share the delete confirmation is open on. */
    val deleting: Share? = null,
    val deleteState: ShareDeleteState = ShareDeleteState.Idle,
    /** The share whose link was just copied, so its button can say it worked. */
    val copiedShareId: Uuid? = null,
)

sealed class ShareEvent {
    /** The sheet came up: back to the list, as it stands now. */
    data object Open : ShareEvent()
    data object Reload : ShareEvent()

    /** Makes a link with the defaults, hands it to the share sheet and opens its settings. */
    data object Create : ShareEvent()
    /** Opens the platform's share sheet with the link of [share]. */
    data class ShareLink(val share: Share) : ShareEvent()
    /** The share sheet of [ShareState.linkToShare] is up. */
    data object LinkShared : ShareEvent()
    data class Copy(val share: Share) : ShareEvent()

    /** Opens the settings of [share]. */
    data class Edit(val share: Share) : ShareEvent()
    data class SetName(val name: String) : ShareEvent()
    data class SetPassword(val password: String) : ShareEvent()
    data class SetRemovePassword(val remove: Boolean) : ShareEvent()
    data class SetAllowMetadataWithoutPassword(val allow: Boolean) : ShareEvent()
    data class SetIncludeLabels(val include: Boolean) : ShareEvent()
    data class SetIncludeAttachments(val include: Boolean) : ShareEvent()
    data class SetExpiry(val expiry: ShareExpiry) : ShareEvent()
    data class SetExpiresOn(val date: LocalDate) : ShareEvent()
    /** Writes the settings and goes back to the list; the link stays the same. */
    data object Save : ShareEvent()
    /** Back to the list without saving. */
    data object CloseEdit : ShareEvent()

    data class AskToDelete(val share: Share) : ShareEvent()
    data object CancelDelete : ShareEvent()
    data object ConfirmDelete : ShareEvent()
}
