package es.jvbabi.overmail.server.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

private const val APPLE_PUSH_KEY_FILE_NAME = "apple-push.json"

/**
 * The key the server sends pushes to iOS with, read from `data/apple-push.json` -- next to the
 * config file and gitignored with it:
 *
 * ```json
 * {"team_id": "…", "key_id": "…", "topic": "<bundle id of the app>", "private_key": "-----BEGIN PRIVATE KEY-----\n…"}
 * ```
 *
 * [privateKey] is the content of the `.p8` file the Apple developer portal hands out for an APNs
 * auth key. Optional: a server without the file sends nothing to iOS.
 */
@Serializable
data class ApplePushKey(
    @SerialName("team_id") val teamId: String,
    @SerialName("key_id") val keyId: String,
    /** The bundle id of the app; APNs refuses a push for any other. */
    @SerialName("topic") val topic: String,
    /** The P-256 key as PKCS#8 in PEM, which every request to APNs is signed with. */
    @SerialName("private_key") val privateKey: String,
) {
    // Not the generated one: that would print the key into a log.
    override fun toString() = "ApplePushKey(teamId=$teamId, keyId=$keyId, topic=$topic)"

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The key, or null when there is no file. A file that is there and cannot be read throws
         * with its path: a broken key should not look like push being switched off.
         */
        fun load(storageDirectory: String = defaultStorageDirectory): ApplePushKey? {
            val file = File(storageDirectory).resolve(APPLE_PUSH_KEY_FILE_NAME)
            if (!file.isFile) return null

            return try {
                json.decodeFromString<ApplePushKey>(file.readText())
            } catch (cause: Exception) {
                throw IllegalStateException("Cannot read the APNs key at ${file.absolutePath}", cause)
            }
        }
    }
}
