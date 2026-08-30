package es.jvbabi.overmail.server.data.notifier

import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.Label
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.ConcurrentHashMap

class EmailLabelNotifier {
    private val channels = ConcurrentHashMap<Email.Id, MutableSharedFlow<EmailLabelEvent>>()

    fun subscribe(emailId: Email.Id): SharedFlow<EmailLabelEvent> {
        // The buffer is what makes tryEmit work: a MutableSharedFlow() without buffer rejects
        // every tryEmit as soon as someone collects, silently dropping all events. No replay —
        // subscribers get the current labels with the initial email payload; only live changes
        // flow through here.
        return channels.computeIfAbsent(emailId) {
            MutableSharedFlow(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        }
    }

    fun notifyLabelUpsert(emailId: Email.Id, label: Label) {
        channels[emailId]?.tryEmit(EmailLabelEvent.Upsert(label))
    }

    fun notifyLabelDelete(emailId: Email.Id, label: Label) {
        channels[emailId]?.tryEmit(EmailLabelEvent.Delete(label))
    }
}

sealed class EmailLabelEvent {
    data class Upsert(val label: Label): EmailLabelEvent()
    data class Delete(val label: Label): EmailLabelEvent()
}