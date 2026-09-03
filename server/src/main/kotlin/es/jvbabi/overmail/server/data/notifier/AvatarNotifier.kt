package es.jvbabi.overmail.server.data.notifier

import es.jvbabi.overmail.server.database.models.EmailAvatar
import es.jvbabi.overmail.server.database.models.EmailUser
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Pictures landing after a stack batch went out. A lookup is a request to a third party, so it
 * happens next to the batch rather than inside it -- the mails are served right away and the
 * avatars arrive over the same socket whenever they arrive.
 *
 * Keyed by address row rather than by mail: one sender fills a mailbox, and its picture is the
 * same in every mail it sent.
 */
class AvatarNotifier {
    private val channels = ConcurrentHashMap<EmailUser.Id, MutableSharedFlow<AvatarEvent>>()

    fun subscribe(emailUserId: EmailUser.Id): SharedFlow<AvatarEvent> {
        // replay = 1, unlike EmailLabelNotifier: a lookup is enqueued right after this and can
        // finish before the collector is attached, and a shared flow without replay drops what it
        // emits while nobody listens. Replaying the last picture costs a duplicate message to a
        // client that already has it, which is an assignment of the same url.
        return channels.computeIfAbsent(emailUserId) {
            MutableSharedFlow(
                replay = 1,
                extraBufferCapacity = 64,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        }
    }

    fun notifyAvatarResolved(
        emailUserId: EmailUser.Id,
        address: String,
        avatarId: EmailAvatar.Id,
        circlePadding: Double,
    ) {
        channels[emailUserId]?.tryEmit(AvatarEvent.Resolved(address, avatarId, circlePadding))
    }
}

sealed class AvatarEvent {
    /**
     * [circlePadding] travels with the id because the lookup just worked it out; a client that got
     * the picture with its batch instead reads the same number off the api answer.
     */
    data class Resolved(
        val address: String,
        val avatarId: EmailAvatar.Id,
        val circlePadding: Double,
    ) : AvatarEvent()
}
