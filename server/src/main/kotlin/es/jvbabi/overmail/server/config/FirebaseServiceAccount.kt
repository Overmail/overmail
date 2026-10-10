package es.jvbabi.overmail.server.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

private const val SERVICE_ACCOUNT_FILE_NAME = "firebase-service.json"

/**
 * The service account the server sends pushes as, read from `data/firebase-service.json` -- the
 * key file as the Firebase console hands it out, next to the config file and gitignored with it.
 *
 * Optional: a server without the file runs without push. It is the key of the app's own Firebase
 * project, so whoever holds it can push to every installation of the app.
 */
@Serializable
data class FirebaseServiceAccount(
    @SerialName("project_id") val projectId: String,
    @SerialName("client_email") val clientEmail: String,
    /** The RSA key as PKCS#8 in PEM, which is what the access token request is signed with. */
    @SerialName("private_key") val privateKey: String,
    @SerialName("token_uri") val tokenUri: String = "https://oauth2.googleapis.com/token",
) {
    // Not the generated one: that would print the key into a log.
    override fun toString() = "FirebaseServiceAccount(projectId=$projectId, clientEmail=$clientEmail)"

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The service account, or null when there is no file. A file that is there and cannot be
         * read throws with its path: a broken key should not look like push being switched off.
         */
        fun load(storageDirectory: String = defaultStorageDirectory): FirebaseServiceAccount? {
            val file = File(storageDirectory).resolve(SERVICE_ACCOUNT_FILE_NAME)
            if (!file.isFile) return null

            return try {
                json.decodeFromString<FirebaseServiceAccount>(file.readText())
            } catch (cause: Exception) {
                throw IllegalStateException("Cannot read the Firebase service account at ${file.absolutePath}", cause)
            }
        }
    }
}
