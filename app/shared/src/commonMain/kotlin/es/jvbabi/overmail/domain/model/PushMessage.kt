package es.jvbabi.overmail.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

/**
 * What a push says, as the server sends it (`jobs/push/PushMessage.kt`): that something happened
 * and the ids to ask about it, never content. Both sides have to agree on the `type` names.
 */
@Serializable
sealed class PushMessage {

    /** Nothing happened: a test notification is shown, which proves the whole way works. */
    @Serializable
    @SerialName("ping")
    data object Ping : PushMessage()
}

/** A push as it arrived: what it says, and which account's server sent it. */
data class ReceivedPush(
    val userId: Uuid,
    val message: PushMessage,
) {
    companion object {
        private const val DATA_USER_ID = "user_id"
        private const val DATA_PAYLOAD = "payload"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Reads the data of a push, or null when this version of the app cannot: a `type` a newer
         * server knows and this app does not is passed over, not an error.
         */
        fun fromData(data: Map<String, String>): ReceivedPush? {
            val userId = data[DATA_USER_ID]?.let(Uuid::parseOrNull) ?: return null
            val payload = data[DATA_PAYLOAD] ?: return null
            val message = try {
                json.decodeFromString<PushMessage>(payload)
            } catch (_: SerializationException) {
                return null
            } catch (_: IllegalArgumentException) {
                return null
            }
            return ReceivedPush(userId, message)
        }
    }
}
