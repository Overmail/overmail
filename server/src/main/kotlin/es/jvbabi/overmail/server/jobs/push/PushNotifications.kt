package es.jvbabi.overmail.server.jobs.push

import kotlin.uuid.Uuid

/**
 * How many mails one push names at most. Firebase takes 4 KB of data per message and an id is 39
 * bytes of it, so a bulk change of 500 mails goes out as several.
 */
private const val EMAILS_PER_PUSH = 50

/**
 * Everything the server pushes, one function per occasion. A caller says what happened and to
 * whom; which [PushMessage] that is and how it gets out is decided here and in the [PushQueue].
 *
 * Nothing here waits: a push is queued and the function returns.
 */
class PushNotifications(private val queue: PushQueue) {

    /** Tells every device of [userId] about the mail [emailId] that arrived in the mailbox [imapAccountId]. */
    fun newEmail(userId: Uuid, emailId: Uuid, imapAccountId: Uuid) =
        queue.enqueue(PushTarget.User(userId), PushMessage.NewEmail(emailId = emailId, imapAccountId = imapAccountId))

    /**
     * Tells every device of [userId] that the mails [emailIds] were read or moved, so they drop
     * the notifications that are dealt with. Call it after the change committed: the app asks
     * the server for the mails as soon as the push arrives.
     */
    fun emailsChanged(userId: Uuid, emailIds: Collection<Uuid>) {
        emailIds.distinct().chunked(EMAILS_PER_PUSH).forEach { chunk ->
            queue.enqueue(PushTarget.User(userId), PushMessage.EmailsChanged(chunk))
        }
    }

    /** Has [target] show a test notification, to see that a push gets through at all. */
    fun ping(target: PushTarget) = queue.enqueue(target, PushMessage.Ping)
}
