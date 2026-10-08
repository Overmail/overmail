package es.jvbabi.overmail.page.email.components.attachments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import es.jvbabi.overmail.domain.model.Attachment
import es.jvbabi.overmail.domain.model.OvermailAccount
import es.jvbabi.overmail.domain.repository.AccountRepository
import es.jvbabi.overmail.domain.repository.EmailsRepository
import es.jvbabi.overmail.domain.repository.FileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okio.Path
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

private val logger = Logger.withTag("AttachmentsViewModel")

/**
 * The attachments on screen: which of them are on the device, which are on their way, and what a
 * tap does -- download and open, open what is here, or cancel what is running. The file itself is
 * kept by the [FileRepository], in the app's cache.
 *
 * Downloads belong to the screen: leaving it cancels them, like closing the mail in the web app.
 */
class AttachmentsViewModel(
    private val accountRepository: AccountRepository,
    private val emailsRepository: EmailsRepository,
    private val fileRepository: FileRepository,
) : ViewModel() {
    /** By attachment id; one that is not in here has not been looked for yet, which reads as [AttachmentState.Remote]. */
    val state: StateFlow<Map<Uuid, AttachmentState>>
        field = MutableStateFlow(emptyMap())

    /** The running downloads, by attachment id. Only touched on the main thread. */
    private val downloads = mutableMapOf<Uuid, Job>()

    /** Finds which of [attachments] are on the device already, so a tap on them opens right away. */
    fun onAttachmentsShown(attachments: List<Attachment>) {
        viewModelScope.launch {
            attachments
                .filter { it.id !in state.value }
                .forEach { attachment ->
                    val cached = fileRepository.getAttachmentFile(attachment) != null
                    setState(attachment.id, if (cached) AttachmentState.Cached else AttachmentState.Remote)
                }
        }
    }

    /** Opens [attachment], downloading it first if it is not here; cancels the download if it is running. */
    fun onClick(attachment: Attachment) {
        downloads[attachment.id]?.takeIf { it.isActive }?.let {
            it.cancel()
            return
        }

        downloads[attachment.id] = viewModelScope.launch {
            // The system may have cleared the cache since it was looked at, so it is asked again.
            val file = fileRepository.getAttachmentFile(attachment) ?: download(attachment) ?: return@launch
            val opened = fileRepository.openFile(file, attachment.contentType)
            setState(attachment.id, if (opened) AttachmentState.Cached else AttachmentState.Failed(AttachmentFailure.NoApp))
        }
    }

    /** The file of [attachment] once it is in the cache, or null when that did not work out. */
    private suspend fun download(attachment: Attachment): Path? {
        val account = accountOf(attachment) ?: run {
            setState(attachment.id, AttachmentState.Failed(AttachmentFailure.Download))
            return null
        }

        setState(attachment.id, AttachmentState.Downloading(0f))
        // Only whole percents reach the screen, there is nothing to see in between.
        var shownPercent = 0
        return try {
            fileRepository
                .saveAttachmentFile(attachment) { sink ->
                    emailsRepository.downloadAttachment(attachment, account, sink) { progress ->
                        val percent = (progress * 100).roundToInt()
                        if (percent == shownPercent) return@downloadAttachment
                        shownPercent = percent
                        setState(attachment.id, AttachmentState.Downloading(progress))
                    }
                }
                .onFailure {
                    logger.w(it) { "Could not download the attachment ${attachment.id}" }
                    setState(attachment.id, AttachmentState.Failed(AttachmentFailure.Download))
                }
                .getOrNull()
        } catch (e: CancellationException) {
            setState(attachment.id, AttachmentState.Remote)
            throw e
        }
    }

    /**
     * The account the mail of [attachment] belongs to. A mail on screen has been read already, so
     * it is known; failing that, the first account, as the rest of the app has no switcher yet.
     */
    private suspend fun accountOf(attachment: Attachment): OvermailAccount? =
        emailsRepository.peekEmail(attachment.emailId)?.overmailAccount
            ?: accountRepository.getAccounts().first().firstOrNull()

    private fun setState(attachmentId: Uuid, attachmentState: AttachmentState) {
        state.update { it + (attachmentId to attachmentState) }
    }
}

/** What there is of one attachment on the device, and so what a tap on it does. */
sealed interface AttachmentState {
    /** Only on the server; a tap downloads and opens it. */
    data object Remote : AttachmentState

    /** In the cache; a tap opens it. */
    data object Cached : AttachmentState

    /** On its way, [progress] from 0 to 1; a tap cancels it. */
    data class Downloading(val progress: Float) : AttachmentState

    /** The last tap did not work out; another one tries again. */
    data class Failed(val reason: AttachmentFailure) : AttachmentState
}

enum class AttachmentFailure {
    /** It did not arrive: no connection, no account, or no room for it. */
    Download,

    /** It is here, but nothing on the device opens a file of its type. */
    NoApp,
}
