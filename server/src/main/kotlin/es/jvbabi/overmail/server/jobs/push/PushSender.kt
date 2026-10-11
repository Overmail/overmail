package es.jvbabi.overmail.server.jobs.push

/**
 * Hands one push to whatever delivers it to a device. The only part that knows about Firebase,
 * so a relay in front of it would be another implementation of this.
 */
fun interface PushSender {
    /**
     * Sends [data] to the installation [token] addresses. Does not throw for a push that could
     * not be delivered -- the [Result] says what became of it.
     */
    suspend fun send(token: String, data: Map<String, String>, isUrgent: Boolean): Result

    sealed class Result {
        data object Sent : Result()

        /** The token addresses nothing anymore: the app was uninstalled, or its data cleared. */
        data object Unregistered : Result()

        /** [isRetryable] when it may work a moment later: the network, a busy or failing service. */
        data class Failed(val reason: String, val isRetryable: Boolean) : Result()
    }

    /** What stands in while the server has no Firebase credentials: push is switched off. */
    data object Disabled : PushSender {
        override suspend fun send(token: String, data: Map<String, String>, isUrgent: Boolean) =
            Result.Failed("Push is not configured", isRetryable = false)
    }
}
