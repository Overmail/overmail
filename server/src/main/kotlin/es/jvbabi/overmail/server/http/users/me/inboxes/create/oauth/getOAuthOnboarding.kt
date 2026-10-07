package es.jvbabi.overmail.server.http.users.me.inboxes.create.oauth

import es.jvbabi.overmail.server.http.api.requireOwnedOAuthOnboardingFromUrl
import io.ktor.openapi.JsonSchema
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What "new inbox" continues on after a sign-in at a provider: the mailbox, without the tokens.
 * Those never leave the server; the folder scan and the submit name the onboarding instead.
 */
fun Route.getOAuthOnboarding() {
    authenticate {
        /**
         * Read the mailbox a sign-in at a provider came back with.
         *
         * Description: What the settings open "new inbox" on when the url carries `_authentikt_session_id`.
         *
         * Tag: Setup
         *
         * Responses:
         *   - 200 [OAuthOnboardingResponse] The mailbox
         */
        get {
            val onboarding = call.requireOwnedOAuthOnboardingFromUrl()
            call.respond(
                OAuthOnboardingResponse(
                    provider = onboarding.provider.id,
                    host = onboarding.provider.imapHost,
                    port = onboarding.provider.imapPort,
                    username = onboarding.address,
                )
            )
        }
    }
}

@Serializable
internal data class OAuthOnboardingResponse(
    @JsonSchema.Description("`microsoft` or `google`")
    @SerialName("provider") val provider: String,
    @SerialName("host") val host: String,
    @SerialName("port") val port: Int,
    @JsonSchema.Description("The address that was signed in to, which is what imap logs in as")
    @SerialName("username") val username: String,
)
