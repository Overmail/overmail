package es.jvbabi.overmail.server.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One entry of the `oauth` section of `data/config.json`, keyed by the id of an
 * `OAuthProvider`: what this server is registered as with that provider.
 *
 * ```json
 * {
 *   "oauth": {
 *     "microsoft": {"client_id": "…", "client_secret": "…"},
 *     "google": {"client_id": "…", "client_secret": "…"}
 *   }
 * }
 * ```
 *
 * A provider without an entry is switched off: the dialog does not offer it and its routes answer
 * 404, so a server nobody registered anywhere still runs.
 */
@Serializable
data class OAuthClientConfig(
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String,
)
