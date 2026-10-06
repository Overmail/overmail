package es.jvbabi.overmail.server.http.oauth

import es.jvbabi.overmail.server.http.api.dependency
import es.jvbabi.overmail.server.http.api.invalidRequest
import es.jvbabi.overmail.server.http.api.queryParameter
import es.jvbabi.overmail.server.http.api.requireOAuthClientFromUrl
import es.jvbabi.overmail.server.oauth.OAuthStateStore
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Where a provider sends the browser back to after the sign-in that `startOAuth` sent it to.
 *
 * Outside `/users/me` and without `authenticate { }`: the request comes from the provider's page,
 * and the state is what says whose sign-in this is. It is consumed on arrival, so a callback
 * answers once.
 *
 * Still a placeholder: it checks the state and stops there. Exchanging the code for tokens and
 * turning them into an inbox comes next.
 */
fun Route.oauthCallback() {
    /**
     * Receive the answer of a provider's sign-in.
     *
     * Description: A placeholder for now. It checks the state and answers, nothing is connected yet.
     *
     * Tag: Setup
     *
     * Query parameters:
     *   - state [String] What the sign-in was sent off with
     *   - code [String] What the provider hands out for the tokens; not read yet
     *
     * Responses:
     *   - 200 text/plain [String] The state checked out, nothing else happens yet
     *   - 400 [es.jvbabi.overmail.server.http.api.ApiErrorBody] An unknown, expired or already used state, or one issued for another provider
     *   - 404 [es.jvbabi.overmail.server.http.api.ApiErrorBody] No such provider, or none this server has a client for
     */
    get {
        val client = call.requireOAuthClientFromUrl()
        val state = call.queryParameter("state") ?: invalidRequest("state", "is missing")

        val pending = call.dependency<OAuthStateStore>().consume(state)
        if (pending == null || pending.provider != client.provider) {
            invalidRequest("state", "is unknown, expired or was issued for another provider")
        }

        call.respondText(
            "Signed in at ${client.provider.id}, connecting the inbox is not implemented yet",
            ContentType.Text.Plain,
            HttpStatusCode.OK,
        )
    }
}
