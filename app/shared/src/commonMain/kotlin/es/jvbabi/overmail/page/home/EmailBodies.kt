package es.jvbabi.overmail.page.home

import es.jvbabi.overmail.domain.model.EmailBody
import es.jvbabi.overmail.domain.model.OvermailAccount
import es.jvbabi.overmail.page.home.components.stack.StackCardBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/** What mails say, fetched with [fetch] as they are asked for and kept for as long as [scope] lives. */
class EmailBodies(
    private val scope: CoroutineScope,
    private val fetch: suspend (emailId: Uuid, account: OvermailAccount) -> Result<EmailBody>,
) {
    val bodies: StateFlow<Map<Uuid, StackCardBody>>
        field = MutableStateFlow(emptyMap())

    /** Fetches what the mail of [emailId] says unless it is here or on its way; one that failed is asked for again. */
    fun load(emailId: Uuid, account: OvermailAccount) {
        val known = bodies.value[emailId]
        if (known != null && known !is StackCardBody.Failed) return
        bodies.update { it + (emailId to StackCardBody.Loading) }

        scope.launch {
            val body = fetch(emailId, account).fold(
                onSuccess = { body -> body.html?.let(StackCardBody::Html) ?: StackCardBody.Text(body.text.orEmpty()) },
                onFailure = { StackCardBody.Failed },
            )
            bodies.update { it + (emailId to body) }
        }
    }
}
