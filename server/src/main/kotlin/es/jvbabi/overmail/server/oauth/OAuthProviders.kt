package es.jvbabi.overmail.server.oauth

import es.jvbabi.overmail.server.config.OAuthClientConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments

private val logger = KotlinLogging.logger {}

/** A provider together with what this server is registered as there. */
class OAuthClient(
    val provider: OAuthProvider,
    val config: OAuthClientConfig,
    /**
     * Where the provider sends the browser back to, `<base_url>/api/oauth/<provider>/callback`.
     * Has to match what the client is registered with there, character for character, and the
     * token exchange has to send it again -- so it is built once, here, and read from here.
     */
    val redirectUri: String,
)

/**
 * The [OAuthProvider]s this server has a client for, out of the `oauth` section of the config.
 *
 * The one place that decides whether a provider is usable: the dialog lists [configured], and a
 * route resolves a provider through [byId], which knows nothing of one without an entry.
 */
class OAuthProviders(clients: Map<String, OAuthClientConfig>, baseUrl: String) {

    private val clients: Map<OAuthProvider, OAuthClient> = buildMap {
        clients.forEach { (id, config) ->
            val provider = OAuthProvider.byId(id)
            // A typo in the config would otherwise just make the provider disappear from the dialog.
            if (provider == null) logger.warn { "Ignoring oauth client for unknown provider '$id'" }
            else put(provider, OAuthClient(provider, config, callbackUrl(baseUrl, provider)))
        }
    }

    /** In declaration order, which is the order the dialog shows them in. */
    val configured: List<OAuthProvider> = OAuthProvider.entries.filter { it in this.clients }

    /** The client for the provider named [id], or null for an unknown one and one without a client alike. */
    fun byId(id: String): OAuthClient? = OAuthProvider.byId(id)?.let(clients::get)

    /**
     * Says at startup which providers are on and what each has to be registered with, so a
     * redirect the provider rejects can be checked against the log instead of guessed at.
     */
    fun logConfigured() {
        if (clients.isEmpty()) logger.info { "No oauth provider configured" }
        configured.forEach { provider ->
            val client = clients.getValue(provider)
            logger.info {
                "OAuth provider '${provider.id}' active: client id ${client.config.clientId}, redirect uri ${client.redirectUri}"
            }
        }
    }

    /** The sign-in page of [client]'s provider, carrying [state] there and back. */
    fun authorizeUrl(client: OAuthClient, state: String): String =
        URLBuilder(client.provider.authorizeUrl).apply {
            parameters.append("client_id", client.config.clientId)
            parameters.append("response_type", "code")
            parameters.append("redirect_uri", client.redirectUri)
            parameters.append("scope", client.provider.scopes.joinToString(" "))
            parameters.append("state", state)
            client.provider.authorizeParameters.forEach { (name, value) -> parameters.append(name, value) }
        }.buildString()
}

/** What `Routes.kt` mounts `oauthCallback` under, as an absolute url. */
private fun callbackUrl(baseUrl: String, provider: OAuthProvider): String =
    URLBuilder(baseUrl).appendPathSegments("api", "oauth", provider.id, "callback").buildString()
