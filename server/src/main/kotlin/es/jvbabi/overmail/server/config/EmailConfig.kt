package es.jvbabi.overmail.server.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The `email` section of `data/config.json`. */
@Serializable
data class EmailConfig(
    @SerialName("smtp") val smtp: SmtpConfig,
)

@Serializable
data class SmtpConfig(
    @SerialName("host") val host: String,
    @SerialName("port") val port: Int,

    /**
     * Implicit TLS, as spoken on port 465. When false the connection starts in the clear and is
     * upgraded through STARTTLS, which is what port 587 expects.
     */
    @SerialName("secure") val secure: Boolean = true,

    @SerialName("auth") val auth: Auth,
) {
    @Serializable
    data class Auth(
        @SerialName("username") val username: String,
        @SerialName("password") val password: String,
    )
}
