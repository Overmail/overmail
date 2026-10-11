package es.jvbabi.overmail.server.jobs.push

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import es.jvbabi.overmail.server.config.FirebaseServiceAccount
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/** What the access token is asked for: sending messages, and nothing else of the project. */
private const val MESSAGING_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"

/** How long before it runs out an access token is replaced, so none expires on its way. */
private val RENEW_BEFORE_EXPIRY = 1.minutes

/**
 * Sends through the HTTP v1 api of Firebase Cloud Messaging, as [account].
 *
 * Two requests and a signature, which is why this talks to the api itself instead of bringing the
 * Admin SDK: the service account signs a request for an access token, and each push is one POST
 * with that token. The token is good for an hour and shared by every push in it.
 *
 * Only data messages: nothing here names a title or a text, see [PushMessage].
 *
 * @param fcmUrl the api's base, replaced in tests.
 */
class FcmPushSender(
    private val account: FirebaseServiceAccount,
    private val client: HttpClient = defaultClient(),
    private val fcmUrl: String = "https://fcm.googleapis.com",
    private val clock: Clock = Clock.System,
) : PushSender {

    private val signingKey: Algorithm = Algorithm.RSA256(null, account.privateKey.toRsaPrivateKey())

    private data class AccessToken(val value: String, val expiresAt: Instant)

    private var accessToken: AccessToken? = null
    private val accessTokenLock = Mutex()

    override suspend fun send(token: String, data: Map<String, String>, isUrgent: Boolean): PushSender.Result {
        return try {
            val response = client.post("$fcmUrl/v1/projects/${account.projectId}/messages:send") {
                bearerAuth(accessToken())
                contentType(ContentType.Application.Json)
                setBody(
                    json.encodeToString(
                        SendRequest(
                            Message(
                                token = token,
                                data = data,
                                android = AndroidConfig(priority = if (isUrgent) "HIGH" else "NORMAL"),
                            )
                        )
                    )
                )
            }
            response.toResult()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (cause: Exception) {
            // The network, a timeout, the token endpoint not answering: all of it may pass.
            PushSender.Result.Failed(cause.message ?: cause.toString(), isRetryable = true)
        }
    }

    private suspend fun HttpResponse.toResult(): PushSender.Result {
        if (status.isSuccess()) return PushSender.Result.Sent

        val body = bodyAsText()
        val error = runCatching { json.decodeFromString<ErrorResponse>(body).error }.getOrNull()
        val errorCode = error?.details?.firstNotNullOfOrNull { it.errorCode }
        val reason = "${status.value} ${errorCode ?: error?.status ?: ""}: ${error?.message ?: body.take(200)}"

        return when {
            // 404 is how Firebase answers for a token that was valid once.
            errorCode == "UNREGISTERED" || status == HttpStatusCode.NotFound -> PushSender.Result.Unregistered
            status == HttpStatusCode.Unauthorized -> {
                // The access token was refused before its time; the next attempt asks for a new one.
                accessTokenLock.withLock { accessToken = null }
                PushSender.Result.Failed(reason, isRetryable = true)
            }
            status == HttpStatusCode.TooManyRequests || status.value >= 500 -> PushSender.Result.Failed(reason, isRetryable = true)
            else -> PushSender.Result.Failed(reason, isRetryable = false)
        }
    }

    /** The access token every push of this hour is sent with, asked for when there is none that lasts. */
    private suspend fun accessToken(): String = accessTokenLock.withLock {
        accessToken?.takeIf { clock.now() < it.expiresAt - RENEW_BEFORE_EXPIRY }?.let { return it.value }

        val now = clock.now()
        val assertion = JWT.create()
            .withIssuer(account.clientEmail)
            .withAudience(account.tokenUri)
            .withClaim("scope", MESSAGING_SCOPE)
            .withIssuedAt(now.toJavaInstant())
            .withExpiresAt((now + 1.hours).toJavaInstant())
            .sign(signingKey)

        val response = client.submitForm(
            url = account.tokenUri,
            formParameters = parameters {
                append("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
                append("assertion", assertion)
            },
        )
        check(response.status.isSuccess()) { "Google refused the service account (${response.status.value}): ${response.bodyAsText().take(200)}" }

        val granted = json.decodeFromString<TokenResponse>(response.bodyAsText())
        accessToken = AccessToken(granted.accessToken, now + granted.expiresIn.seconds)
        granted.accessToken
    }

    @Serializable
    private data class SendRequest(@SerialName("message") val message: Message)

    @Serializable
    private data class Message(
        @SerialName("token") val token: String,
        @SerialName("data") val data: Map<String, String>,
        @SerialName("android") val android: AndroidConfig,
    )

    @Serializable
    private data class AndroidConfig(@SerialName("priority") val priority: String)

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("expires_in") val expiresIn: Long,
    )

    @Serializable
    private data class ErrorResponse(@SerialName("error") val error: Error) {
        @Serializable
        data class Error(
            @SerialName("message") val message: String? = null,
            @SerialName("status") val status: String? = null,
            @SerialName("details") val details: List<Detail> = emptyList(),
        )

        /** Only the detail of type `FcmError` carries one. */
        @Serializable
        data class Detail(@SerialName("errorCode") val errorCode: String? = null)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        private fun defaultClient() = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 10_000
            }
        }
    }
}

/** The key of a service account file: PKCS#8, base64 between the PEM lines. */
private fun String.toRsaPrivateKey(): RSAPrivateKey {
    val encoded = lineSequence().filterNot { it.startsWith("-----") }.joinToString("").trim()
    val spec = PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded))
    return KeyFactory.getInstance("RSA").generatePrivate(spec) as RSAPrivateKey
}
