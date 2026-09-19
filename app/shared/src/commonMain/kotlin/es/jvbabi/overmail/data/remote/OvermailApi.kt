package es.jvbabi.overmail.data.remote

import es.jvbabi.overmail.BuildKonfig
import es.jvbabi.overmail.domain.model.LoginCode
import es.jvbabi.overmail.domain.model.LoginResult
import es.jvbabi.overmail.domain.model.OvermailAccount
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Everything the app asks the server for. The routes all live under `/api`, which is what Caddy
 * forwards to Ktor -- see deploy/Caddyfile.
 */
class OvermailApi(
    private val httpClient: HttpClient,
) {
    private val baseUrl = BuildKonfig.SERVER_URL.trimEnd('/')

    /** Whether the server answers at all. The one route that needs no session. */
    suspend fun isHealthy(): Boolean = runCatching {
        val response = httpClient.get("$baseUrl/api/health")
        response.status.isSuccess() && response.bodyAsText() == "ok"
    }.getOrDefault(false)

    /**
     * Trades a sign-in code for a session token: `GET /api/auth/instant-auth?code=`.
     *
     * The host comes from [code] and not from [baseUrl] -- the app signs in to whichever Overmail
     * showed the QR code, which is not necessarily the one it was built against.
     *
     * The code is spent on the server whether or not this returns [LoginResult.Success], so a
     * retry needs a new one.
     */
    suspend fun redeemLoginCode(code: LoginCode): LoginResult {
        val response = runCatching {
            httpClient.get("${code.serverUrl.trimEnd('/')}/api/auth/instant-auth") {
                parameter("code", code.code)
            }
        }.getOrElse { return LoginResult.ServerUnreachable }

        if (!response.status.isSuccess()) return when (response.status) {
            // The two the server answers a bad code with, see http/auth/instantAuth.kt.
            HttpStatusCode.NotFound -> LoginResult.UnknownCode
            HttpStatusCode.Gone -> LoginResult.ExpiredCode
            else -> LoginResult.Failed
        }

        val body = runCatching { response.body<InstantAuthResponse>() }
            .getOrElse { return LoginResult.Failed }

        return LoginResult.Success(
            OvermailAccount(
                // The server's user id, so signing in again on the same server replaces the
                // account instead of adding a second one for it.
                id = body.userId,
                serverUrl = code.serverUrl,
                username = body.username,
                accessToken = body.token,
            )
        )
    }
}

/** The half of the server's answer this app stores; `email` is ignored, see the json config. */
@Serializable
private data class InstantAuthResponse(
    @SerialName("token") val token: String,
    @SerialName("user_id") val userId: Uuid,
    @SerialName("username") val username: String,
)
