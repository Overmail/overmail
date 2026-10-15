package es.jvbabi.overmail.server.jobs.push

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import es.jvbabi.overmail.server.config.ApplePushKey
import es.jvbabi.overmail.server.database.models.Session
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.http.HttpClient.Version
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/**
 * How long a provider token is used. APNs takes one for an hour and refuses a new one more often
 * than every 20 minutes, so it is replaced well inside that window.
 */
private val PROVIDER_TOKEN_LIFETIME = 40.minutes

/**
 * Sends to the Apple Push Notification service, signed with [key].
 *
 * Token-based: every request carries a JWT the key signed (ES256), shared by every push for
 * [PROVIDER_TOKEN_LIFETIME]. A device token is only valid against the APNs that issued it, so
 * where a push goes depends on the [Session.Client.ApnsEnvironment] the device registered with.
 *
 * @param urls the APNs base of each environment, replaced in tests.
 */
class ApnsPushSender(
    private val key: ApplePushKey,
    private val client: HttpClient = defaultClient(),
    private val urls: (Session.Client.ApnsEnvironment) -> String = ::apnsUrl,
    private val clock: Clock = Clock.System,
) : ApplePushSender {

    private val signingKey: Algorithm = Algorithm.ECDSA256(null, key.privateKey.toEcPrivateKey())

    private data class ProviderToken(val value: String, val issuedAt: Instant)

    private var providerToken: ProviderToken? = null
    private val providerTokenLock = Mutex()

    override suspend fun send(device: Session.Client.ApplePushRegistration, notification: ApplePushNotification): PushSender.Result {
        return try {
            val response = client.post("${urls(device.environment)}/3/device/${device.token}") {
                header(HttpHeaders.Authorization, "bearer ${providerToken()}")
                header("apns-topic", key.topic)
                header("apns-push-type", if (notification.isVisible) "alert" else "background")
                // 5 lets iOS wait for a moment that costs less battery; a background push must be 5.
                header("apns-priority", if (notification.isUrgent && notification.isVisible) "10" else "5")
                contentType(ContentType.Application.Json)
                setBody(notification.payload)
            }
            response.toResult()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (cause: Exception) {
            // The network or a timeout: both may pass.
            PushSender.Result.Failed(cause.message ?: cause.toString(), isRetryable = true)
        }
    }

    private suspend fun HttpResponse.toResult(): PushSender.Result {
        if (status.isSuccess()) return PushSender.Result.Sent

        val body = bodyAsText()
        val reason = runCatching { json.decodeFromString<ErrorResponse>(body).reason }.getOrNull()
        val description = "${status.value} ${reason ?: body.take(200)}"

        return when {
            // The app is gone from the device, or the token was never one of this environment.
            status == HttpStatusCode.Gone || reason == "BadDeviceToken" || reason == "Unregistered" -> PushSender.Result.Unregistered
            reason == "ExpiredProviderToken" || reason == "InvalidProviderToken" -> {
                // The next attempt signs a new one.
                providerTokenLock.withLock { providerToken = null }
                PushSender.Result.Failed(description, isRetryable = true)
            }
            status == HttpStatusCode.TooManyRequests || status.value >= 500 -> PushSender.Result.Failed(description, isRetryable = true)
            else -> PushSender.Result.Failed(description, isRetryable = false)
        }
    }

    private suspend fun providerToken(): String = providerTokenLock.withLock {
        val now = clock.now()
        providerToken?.takeIf { now < it.issuedAt + PROVIDER_TOKEN_LIFETIME }?.let { return it.value }

        val token = JWT.create()
            .withKeyId(key.keyId)
            .withIssuer(key.teamId)
            .withIssuedAt(now.toJavaInstant())
            .sign(signingKey)
        providerToken = ProviderToken(token, now)
        token
    }

    @Serializable
    private data class ErrorResponse(@SerialName("reason") val reason: String? = null)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        private fun apnsUrl(environment: Session.Client.ApnsEnvironment) = when (environment) {
            Session.Client.ApnsEnvironment.Sandbox -> "https://api.sandbox.push.apple.com"
            Session.Client.ApnsEnvironment.Production -> "https://api.push.apple.com"
        }

        private fun defaultClient() = HttpClient(Java) {
            engine {
                protocolVersion = Version.HTTP_2
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 10_000
            }
        }
    }
}

/** The key of a `.p8` file: PKCS#8, base64 between the PEM lines. */
private fun String.toEcPrivateKey(): ECPrivateKey {
    val encoded = lineSequence().filterNot { it.startsWith("-----") }.joinToString("").trim()
    val spec = PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded))
    return KeyFactory.getInstance("EC").generatePrivate(spec) as ECPrivateKey
}
