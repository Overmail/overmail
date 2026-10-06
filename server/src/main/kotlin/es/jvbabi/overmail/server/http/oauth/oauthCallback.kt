package es.jvbabi.overmail.server.http.oauth

import es.jvbabi.overmail.server.http.api.dependency
import es.jvbabi.overmail.server.http.api.invalidRequest
import es.jvbabi.overmail.server.http.api.queryParameter
import es.jvbabi.overmail.server.http.api.requireOAuthClientFromUrl
import es.jvbabi.overmail.server.oauth.OAuthOnboardingStore
import es.jvbabi.overmail.server.oauth.OAuthProviders
import es.jvbabi.overmail.server.oauth.OAuthStateStore
import es.jvbabi.overmail.server.oauth.OAuthTokenClient
import es.jvbabi.overmail.server.oauth.OAuthTokenException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.URLBuilder
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

private val logger = KotlinLogging.logger {}

/** The query parameter that reopens "new inbox" on a finished sign-in, see `EmailAccountsSettings.svelte`. */
const val CONTINUE_ONBOARDING_PARAMETER = "continue_onboarding_oauth_imap_account"

/**
 * Where a provider sends the browser back to after the sign-in that `startOAuth` sent it to.
 *
 * Outside `/users/me` and without `authenticate { }`: the request comes from the provider's page,
 * and the state is what says whose sign-in this is. It is consumed on arrival, before the code is
 * redeemed, so a callback answers once however it ends.
 *
 * The tokens stay on the server, in [OAuthOnboardingStore]. The browser goes back to the email
 * account settings with nothing but the id they are kept under, and "new inbox" continues from
 * there at the folders -- the sign-in at the provider is what the server and credentials steps
 * would have checked.
 */
fun Route.oauthCallback() {
    /**
     * Receive the answer of a provider's sign-in.
     *
     * Description: Checks the state, trades the code for tokens and sends the browser back to the email account settings, where "new inbox" continues with them.
     *
     * Tag: Setup
     *
     * Query parameters:
     *   - state [String] What the sign-in was sent off with
     *   - code [String] What the provider hands out for the tokens
     *   - error [String] What the provider sends instead of a code, e.g. `access_denied` when the user said no
     *
     * Responses:
     *   - 302 Redirect to the email account settings, carrying `continue_onboarding_oauth_imap_account` unless the sign-in was cancelled
     *   - 400 [es.jvbabi.overmail.server.http.api.ApiErrorBody] An unknown, expired or already used state, one issued for another provider, a code the provider did not accept, or a sign-in that named no address
     *   - 404 [es.jvbabi.overmail.server.http.api.ApiErrorBody] No such provider, or none this server has a client for
     */
    get {
        val client = call.requireOAuthClientFromUrl()
        val state = call.queryParameter("state") ?: invalidRequest("state", "is missing")

        val pending = call.dependency<OAuthStateStore>().consume(state)
        if (pending == null || pending.provider != client.provider) {
            invalidRequest("state", "is unknown, expired or was issued for another provider")
        }

        val settings = URLBuilder(call.dependency<OAuthProviders>().baseUrl).apply {
            parameters.append("settings", "email-accounts")
        }

        // Cancelling on the provider's page comes back here too, with an error instead of a code.
        // Back to where the user came from, then, with nothing to continue.
        if (call.queryParameter("error") != null) {
            call.respondRedirect(settings.buildString())
            return@get
        }

        val code = call.queryParameter("code") ?: invalidRequest("code", "is missing")
        val tokens = try {
            call.dependency<OAuthTokenClient>().exchangeCode(client, code)
        } catch (refused: OAuthTokenException) {
            invalidRequest("code", "was not accepted by ${client.provider.id}: ${refused.error}")
        }
        val address = tokens.mailboxAddress()
            ?: invalidRequest("id_token", "${client.provider.id} did not name the mailbox that was signed in to")
        logger.info { "OAuth sign-in at ${client.provider.id} for user ${pending.userId}: $tokens" }

        val onboardingId = call.dependency<OAuthOnboardingStore>().create(
            OAuthOnboardingStore.Onboarding(pending.userId, client.provider, address, tokens)
        )
        settings.parameters.append(CONTINUE_ONBOARDING_PARAMETER, onboardingId)
        call.respondRedirect(settings.buildString())
    }
}
