package es.jvbabi.overmail.server.config

import es.jvbabi.overmail.server.database.DatabaseConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

private const val DEFAULT_STORAGE_DIRECTORY = "./data"
private const val CONFIG_FILE_NAME = "config.json"

/**
 * Everything the server reads out of `data/config.json`. The file holds secrets and is therefore
 * gitignored; [load] fails with a readable message instead of falling back to defaults, so a
 * missing or broken file cannot turn into a connection to the wrong database.
 */
@Serializable
data class ApplicationConfig(
    /** Public URL the app is reached under; authentikt builds its redirects from it. */
    @SerialName("base_url") val baseUrl: String,

    @SerialName("database") val database: DatabaseConfig,
    @SerialName("email") val email: EmailConfig,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun load(storageDirectory: String = DEFAULT_STORAGE_DIRECTORY): ApplicationConfig {
            val file = File(storageDirectory).resolve(CONFIG_FILE_NAME)
            require(file.isFile) { "Missing config file at ${file.absolutePath}" }

            return try {
                // Sections this class does not model yet must not break parsing.
                json.decodeFromString<ApplicationConfig>(file.readText())
            } catch (cause: Exception) {
                throw IllegalStateException("Cannot read config file at ${file.absolutePath}", cause)
            }
        }
    }
}
