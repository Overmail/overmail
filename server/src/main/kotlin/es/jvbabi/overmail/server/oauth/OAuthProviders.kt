package es.jvbabi.overmail.server.oauth

import es.jvbabi.overmail.server.config.OAuthClientConfig
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/** A provider together with what this server is registered as there. */
class OAuthClient(
    val provider: OAuthProvider,
    val config: OAuthClientConfig,
    val endpoints: OAuthEndpoints,
)

/**
 * The [OAuthProvider]s this server has a client for, out of the `oauth` section of the config.
 *
 * The one place that decides whether a provider is usable: the dialog lists [configured], the
 * sign-in flow has a step for each of [clients], and a route resolves a provider through [byId],
 * which knows nothing of one without an entry.
 */
class OAuthProviders(
    clients: Map<String, OAuthClientConfig>,
    /** Where the app is reached; the sign-in sends the browser back into it. */
    val baseUrl: String,
    /** Where each provider is reached. Its own endpoints, unless a test stands in for it. */
    endpoints: (OAuthProvider) -> OAuthEndpoints = OAuthProvider::endpoints,
) {

    private val clientsByProvider: Map<OAuthProvider, OAuthClient> = buildMap {
        clients.forEach { (id, config) ->
            val provider = OAuthProvider.byId(id)
            // A typo in the config would otherwise just make the provider disappear from the dialog.
            if (provider == null) logger.warn { "Ignoring oauth client for unknown provider '$id'" }
            else put(provider, OAuthClient(provider, config, endpoints(provider)))
        }
    }

    /** In declaration order, which is the order the dialog shows them in. */
    val configured: List<OAuthProvider> = OAuthProvider.entries.filter { it in clientsByProvider }

    /** The clients of [configured], in the same order. */
    val clients: List<OAuthClient> = configured.map(clientsByProvider::getValue)

    /** The client for the provider named [id], or null for an unknown one and one without a client alike. */
    fun byId(id: String): OAuthClient? = OAuthProvider.byId(id)?.let(clientsByProvider::get)

    /**
     * Says at startup which providers are on. The redirect uri each has to be registered with is
     * logged by authentikt when it mounts the callback, see [installOAuthOnboardings].
     */
    fun logConfigured() {
        if (clients.isEmpty()) logger.info { "No oauth provider configured" }
        clients.forEach { client ->
            logger.info { "OAuth provider '${client.provider.id}' active: client id ${client.config.clientId}" }
        }
    }
}
