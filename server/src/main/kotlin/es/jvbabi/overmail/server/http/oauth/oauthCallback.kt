package es.jvbabi.overmail.server.http.oauth

import es.jvbabi.overmail.server.http.api.dependency
import es.jvbabi.overmail.server.http.api.invalidRequest
import es.jvbabi.overmail.server.http.api.queryParameter
import es.jvbabi.overmail.server.http.api.requireOAuthClientFromUrl
import es.jvbabi.overmail.server.oauth.OAuthStateStore
import es.jvbabi.overmail.server.oauth.OAuthTokenClient
import es.jvbabi.overmail.server.oauth.OAuthTokenException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

private val logger = KotlinLogging.logger {}

/**
 * Where a provider sends the browser back to after the sign-in that `startOAuth` sent it to.
 *
 * Outside `/users/me` and without `authenticate { }`: the request comes from the provider's page,
 * and the state is what says whose sign-in this is. It is consumed on arrival, before the code is
 * redeemed, so a callback answers once however it ends.
 *
 * Gets as far as the bearer. Keeping the tokens and turning them into an inbox comes next.
 */
fun Route.oauthCallback() {
    /**
     * Receive the answer of a provider's sign-in.
     *
     * Description: Checks the state and trades the code for tokens. Nothing is connected yet, so a successful sign-in only says so.
     *
     * Tag: Setup
     *
     * Query parameters:
     *   - state [String] What the sign-in was sent off with
     *   - code [String] What the provider hands out for the tokens
     *   - error [String] What the provider sends instead of a code, e.g. `access_denied` when the user said no
     *
     * Responses:
     *   - 200 text/plain [String] The sign-in worked and there is a bearer, or the user cancelled it
     *   - 400 [es.jvbabi.overmail.server.http.api.ApiErrorBody] An unknown, expired or already used state, one issued for another provider, or a code the provider did not accept
     *   - 404 [es.jvbabi.overmail.server.http.api.ApiErrorBody] No such provider, or none this server has a client for
     */
    get {
        val client = call.requireOAuthClientFromUrl()
        val state = call.queryParameter("state") ?: invalidRequest("state", "is missing")

        val pending = call.dependency<OAuthStateStore>().consume(state)
        if (pending == null || pending.provider != client.provider) {
            invalidRequest("state", "is unknown, expired or was issued for another provider")
        }

        // Cancelling on the provider's page comes back here too, with an error instead of a code.
        val error = call.queryParameter("error")
        if (error != null) {
            call.respondText("Sign-in at ${client.provider.id} ended without access: $error", ContentType.Text.Plain)
            return@get
        }

        val code = call.queryParameter("code") ?: invalidRequest("code", "is missing")
        val tokens = try {
            call.dependency<OAuthTokenClient>().exchangeCode(client, code)
        } catch (refused: OAuthTokenException) {
            invalidRequest("code", "was not accepted by ${client.provider.id}: ${refused.error}")
        }
        logger.info { "OAuth sign-in at ${client.provider.id} for user ${pending.userId}: $tokens" }

        call.respondText(
            "Signed in at ${client.provider.id}, connecting the inbox is not implemented yet",
            ContentType.Text.Plain,
            HttpStatusCode.OK,
        )
    }
}
