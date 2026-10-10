package es.jvbabi.overmail.server.jobs.push

/**
 * Everything the server pushes, one function per occasion. A caller says what happened and to
 * whom; which [PushMessage] that is and how it gets out is decided here and in the [PushQueue].
 *
 * Nothing here waits: a push is queued and the function returns.
 */
class PushNotifications(private val queue: PushQueue) {

    /** Has [target] show a test notification, to see that a push gets through at all. */
    fun ping(target: PushTarget) = queue.enqueue(target, PushMessage.Ping)
}
