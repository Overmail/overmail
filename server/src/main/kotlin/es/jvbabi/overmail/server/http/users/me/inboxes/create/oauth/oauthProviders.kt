package es.jvbabi.overmail.server.http.users.me.inboxes.create.oauth

import es.jvbabi.overmail.server.http.api.dependency
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUser
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import es.jvbabi.overmail.server.http.api.requireOAuthClientFromUrl
import es.jvbabi.overmail.server.oauth.OAuthProviders
import es.jvbabi.overmail.server.oauth.OAuthStateStore
import io.ktor.openapi.JsonSchema
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The providers "new inbox" offers a sign-in for: those with a client in the config, and no others. */
fun Route.getOAuthProviders() {
    authenticate {
        /**
         * List the providers an inbox can be connected through by signing in there.
         *
         * Description: Only providers this server has a client for. Empty when none is set up.
         *
         * Tag: Setup
         *
         * Responses:
         *   - 200 [OAuthProvidersResponse] The providers
         */
        get {
            call.requireAuthenticatedUser()
            val providers = call.dependency<OAuthProviders>().configured
            call.respond(OAuthProvidersResponse(providers.map { OAuthProvidersResponse.Provider(it.id) }))
        }
    }
}

/**
 * Sends the browser off to sign in at a provider.
 *
 * A plain link from the dialog rather than a call that answers with a url: the browser has to
 * leave for the provider's page anyway, and this way it never sees the client id or the state
 * before it does. The state is what the callback finds the user by, see [OAuthStateStore].
 */
fun Route.startOAuth() {
    authenticate {
        /**
         * Sign in at a provider to connect an inbox.
         *
         * Description: Redirects to the provider's sign-in page, which sends the browser back to `/api/oauth/{provider}/callback`.
         *
         * Tag: Setup
         *
         * Responses:
         *   - 302 Redirect to the provider's sign-in page
         *   - 404 [es.jvbabi.overmail.server.http.api.ApiErrorBody] No such provider, or none this server has a client for
         */
        get {
            val client = call.requireOAuthClientFromUrl()
            val userId = call.requireAuthenticatedUserId()

            val providers = call.dependency<OAuthProviders>()
            val state = call.dependency<OAuthStateStore>().create(userId, client.provider)
            call.respondRedirect(providers.authorizeUrl(client, state))
        }
    }
}

@Serializable
internal data class OAuthProvidersResponse(
    @SerialName("providers") val providers: List<Provider>,
) {
    @Serializable
    data class Provider(
        @JsonSchema.Description("`microsoft` or `google`; what `/create/oauth/{provider}` is called with")
        @SerialName("id") val id: String,
    )
}
