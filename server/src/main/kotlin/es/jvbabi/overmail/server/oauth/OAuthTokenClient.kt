package es.jvbabi.overmail.server.oauth

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/**
 * What a provider's token endpoint answers, as RFC 6749 names it. Only [accessToken] is
 * guaranteed; the rest depends on the provider and on the scopes that were granted.
 */
@Serializable
data class OAuthTokens(
    /** The bearer imap logs in with, through `AUTHENTICATE XOAUTH2`. */
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String,
    /** Seconds the [accessToken] is good for, from the moment it was issued. */
    @SerialName("expires_in") val expiresIn: Long? = null,
    /** What gets a new [accessToken] once this one ran out. Only with offline access, see [OAuthProvider]. */
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("scope") val scope: String? = null,
    /** Who signed in, as a signed jwt -- the mailbox address is in its `email` claim. Only with `openid`. */
    @SerialName("id_token") val idToken: String? = null,
) {
    /**
     * The address of the mailbox that was signed in to, out of the [idToken]: `email`, or for a
     * Microsoft work account without one the `preferred_username`, which is its sign-in address.
     *
     * The signature is not checked, and need not be: the token came straight from the provider's
     * token endpoint over tls, which OpenID Connect accepts in place of it (Core 3.1.3.7).
     */
    fun mailboxAddress(): String? {
        val payload = idToken?.split('.')?.getOrNull(1) ?: return null
        val claims = runCatching {
            Json.parseToJsonElement(Base64.getUrlDecoder().decode(payload).decodeToString()).jsonObject
        }.getOrNull() ?: return null
        return listOf("email", "preferred_username")
            .firstNotNullOfOrNull { claim -> claims[claim]?.jsonPrimitive?.contentOrNull?.takeIf { '@' in it } }
    }

    override fun toString() =
        "OAuthTokens(tokenType=$tokenType, expiresIn=$expiresIn, refreshToken=${refreshToken != null}, scope=$scope)"
}

/** The provider refused to hand out tokens. [error] is its code, e.g. `invalid_grant` for a used or expired code. */
class OAuthTokenException(val error: String, description: String?) :
    RuntimeException("Token request refused: $error${description?.let { " ($it)" }.orEmpty()}")

/** Talks to the token endpoints of the [OAuthProvider]s. */
class OAuthTokenClient(private val http: HttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Trades the [code] a sign-in at [client]'s provider came back with for tokens.
     *
     * The redirect uri is sent again although nothing is redirected: the provider checks it against
     * the one the code was issued for, so a code caught on the way cannot be redeemed elsewhere.
     */
    suspend fun exchangeCode(client: OAuthClient, code: String): OAuthTokens {
        val response = http.submitForm(
            url = client.provider.tokenUrl,
            formParameters = parameters {
                append("grant_type", "authorization_code")
                append("code", code)
                append("redirect_uri", client.redirectUri)
                append("client_id", client.config.clientId)
                append("client_secret", client.config.clientSecret)
            },
        )

        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val error = runCatching { json.decodeFromString<TokenError>(body) }.getOrNull()
            throw OAuthTokenException(error?.error ?: "http_${response.status.value}", error?.description)
        }
        return json.decodeFromString<OAuthTokens>(body)
    }

    @Serializable
    private data class TokenError(
        @SerialName("error") val error: String,
        @SerialName("error_description") val description: String? = null,
    )
}
