package es.jvbabi.overmail.page.home

import androidx.compose.ui.graphics.ImageBitmap
import es.jvbabi.overmail.domain.model.EmailBody
import es.jvbabi.overmail.domain.model.OvermailAccount
import es.jvbabi.overmail.page.home.components.stack.StackCardBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * What mails say, fetched with [fetch] as they are asked for and kept for as long as [scope] lives.
 * An html mail is shown as a picture, which [picture] makes right after the body is here; a picture
 * is large, so only those [keepPictures] names are held on to.
 */
class EmailBodies(
    private val scope: CoroutineScope,
    private val picture: suspend (emailId: Uuid, html: String, order: () -> Int) -> ImageBitmap?,
    private val fetch: suspend (emailId: Uuid, account: OvermailAccount) -> Result<EmailBody>,
) {
    val bodies: StateFlow<Map<Uuid, StackCardBody>>
        field = MutableStateFlow(emptyMap())

    private val pictureJobs = mutableMapOf<Uuid, Job>()

    /** Takes [body] as what the mail of [emailId] says, unless something is known of it already. */
    fun seed(emailId: Uuid, body: StackCardBody) {
        bodies.update { if (emailId in it) it else it + (emailId to body) }
    }

    /**
     * Fetches what the mail of [emailId] says unless it is here or on its way, and pictures an html
     * one; one that failed is asked for again, and so is a picture that was let go. [order] is the
     * mail's place in line for its picture, see [es.jvbabi.overmail.data.picture.EmailPictures].
     */
    fun load(emailId: Uuid, account: OvermailAccount, order: () -> Int = { 0 }) {
        when (val known = bodies.value[emailId]) {
            null, StackCardBody.Failed -> Unit
            is StackCardBody.Html -> {
                if (known.picture == null && pictureJobs[emailId]?.isActive != true) loadPicture(emailId, known.html, order)
                return
            }
            else -> return
        }
        bodies.update { it + (emailId to StackCardBody.Loading) }

        scope.launch {
            val body = fetch(emailId, account).getOrElse {
                bodies.update { it + (emailId to StackCardBody.Failed) }
                return@launch
            }
            val html = body.html
            if (html == null) {
                bodies.update { it + (emailId to StackCardBody.Text(body.text.orEmpty())) }
                return@launch
            }
            bodies.update { it + (emailId to StackCardBody.Html(html)) }
            loadPicture(emailId, html, order)
        }
    }

    /**
     * Holds on to the pictures of [emailIds] only, and stops making any other; the rest go back to
     * their html, and are pictured again -- from the cache, mostly -- once they are loaded again.
     */
    fun keepPictures(emailIds: Set<Uuid>) {
        pictureJobs.keys.filter { it !in emailIds }.forEach { pictureJobs.remove(it)?.cancel() }
        bodies.update { known ->
            known.mapValues { (id, body) ->
                if (id !in emailIds && body is StackCardBody.Html && body.picture != null) body.copy(picture = null) else body
            }
        }
    }

    private fun loadPicture(emailId: Uuid, html: String, order: () -> Int) {
        pictureJobs[emailId] = scope.launch {
            val made = picture(emailId, html, order)
            // One that would not render is told like a body that would not load, and tried again with it.
            bodies.update { it + (emailId to (made?.let { StackCardBody.Html(html, it) } ?: StackCardBody.Failed)) }
            pictureJobs.remove(emailId)
        }
    }
}
