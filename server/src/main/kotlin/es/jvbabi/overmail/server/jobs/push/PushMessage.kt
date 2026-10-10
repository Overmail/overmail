package es.jvbabi.overmail.server.jobs.push

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

/**
 * What a push says: that something happened, and the ids to ask the server about it.
 *
 * Never content. A push travels through Google, so a subject, a sender or a preview has no place
 * in it -- the app fetches those from its homeserver with its own session. A new kind of push is
 * a new subclass here and a function in [PushNotifications]; an app that does not know the `type`
 * yet ignores the push.
 */
@Serializable
sealed class PushMessage {

    /** Nothing happened: the app shows a test notification, which proves the whole way works. */
    @Serializable
    @SerialName("ping")
    data object Ping : PushMessage()

    /**
     * Whether the push has to wake a sleeping device. Only for what ends in a notification the
     * user sees: Android stops delivering urgent pushes to an app that shows nothing for them.
     */
    val isUrgent: Boolean
        get() = when (this) {
            Ping -> true
        }

    /**
     * The message as it is handed to a [PushSender]: Firebase takes a flat map of strings, so the
     * payload is one json value in it. [userId] is whose push it is -- an app signed in to
     * several accounts has to know which session to ask with.
     */
    fun toData(userId: Uuid): Map<String, String> = mapOf(
        DATA_USER_ID to userId.toString(),
        DATA_PAYLOAD to json.encodeToString(serializer(), this),
    )

    companion object {
        const val DATA_USER_ID = "user_id"
        const val DATA_PAYLOAD = "payload"

        private val json = Json { encodeDefaults = true }
    }
}

/** Who a push is for. Resolved into devices when it is sent, not when it is queued. */
sealed class PushTarget {
    /** Every device of every user. */
    data object Everyone : PushTarget()

    /** Every device [userId] is signed in on. */
    data class User(val userId: Uuid) : PushTarget()

    /** The one device behind [sessionId]. */
    data class Session(val sessionId: Uuid) : PushTarget()
}
