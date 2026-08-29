package es.jvbabi.overmail.server.database

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The `database` section of `data/config.json`. No defaults for the credentials on purpose:
 * a missing entry should fail loudly instead of silently connecting somewhere else.
 */
@Serializable
data class DatabaseConfig(
    @SerialName("host") val host: String,
    @SerialName("port") val port: Int = 5432,
    @SerialName("database") val database: String,
    @SerialName("username") val user: String,
    @SerialName("password") val password: String,
) {
    val jdbcUrl: String get() = "jdbc:postgresql://$host:$port/$database"
}
