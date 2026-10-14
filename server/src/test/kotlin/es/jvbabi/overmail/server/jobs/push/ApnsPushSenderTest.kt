package es.jvbabi.overmail.server.jobs.push

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import es.jvbabi.overmail.server.config.ApplePushKey
import es.jvbabi.overmail.server.database.models.Session
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.security.interfaces.ECPublicKey
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** What goes to Apple, and what its answers are taken to mean. APNs itself is a local route. */
class ApnsPushSenderTest {

    private val keys = deviceKeys()

    private val key = ApplePushKey(
        teamId = "TEAM123456",
        keyId = "KEY1234567",
        topic = "es.jvbabi.overmail",
        privateKey = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keys.private.encoded) +
            "\n-----END PRIVATE KEY-----\n",
    )

    private val device = Session.Client.ApplePushRegistration(
        token = "ab".repeat(32),
        environment = Session.Client.ApnsEnvironment.Sandbox,
        publicKey = deviceKeys().publicX963Base64(),
    )
    private val notification = ApplePushNotification(payload = """{"aps":{}}""", isUrgent = true)

    private data class Request(val host: String, val path: String, val headers: Map<String, String?>, val body: String)

    private val requests = mutableListOf<Request>()

    /** The answers of APNs, in order; 200 once they are used up. */
    private val answers = ArrayDeque<Pair<HttpStatusCode, String>>()

    private var now = Instant.parse("2026-10-14T12:00:00Z")
    private val clock = object : Clock {
        override fun now() = now
    }

    @Test
    fun `a push goes to the device on the apns of its environment, signed with the key`() = testApplication {
        val result = sender().send(device, notification)

        assertEquals(PushSender.Result.Sent, result)
        val request = requests.single()
        assertEquals("sandbox", request.host)
        assertEquals("/3/device/${device.token}", request.path)
        assertEquals("es.jvbabi.overmail", request.headers["apns-topic"])
        assertEquals("alert", request.headers["apns-push-type"])
        assertEquals("10", request.headers["apns-priority"])
        assertEquals(notification.payload, request.body)

        val token = JWT.decode(request.headers.getValue(HttpHeaders.Authorization)!!.removePrefix("bearer "))
        Algorithm.ECDSA256(keys.public as ECPublicKey, null).verify(token)
        assertEquals("ES256", token.algorithm)
        assertEquals("KEY1234567", token.keyId)
        assertEquals("TEAM123456", token.issuer)
    }

    @Test
    fun `a production device is sent to on production, and a push that may wait says so`() = testApplication {
        sender().send(device.copy(environment = Session.Client.ApnsEnvironment.Production), notification.copy(isUrgent = false))

        assertEquals("production", requests.single().host)
        assertEquals("5", requests.single().headers["apns-priority"])
    }

    @Test
    fun `a push that shows nothing goes as a background push`() = testApplication {
        sender().send(device, notification.copy(isVisible = false))

        assertEquals("background", requests.single().headers["apns-push-type"])
        assertEquals("5", requests.single().headers["apns-priority"])
    }

    @Test
    fun `the provider token is signed once and again before apns would refuse it`() = testApplication {
        val sender = sender()

        sender.send(device, notification)
        now += 30.minutes
        sender.send(device, notification)
        now += 15.minutes
        sender.send(device, notification)

        val tokens = requests.map { it.headers[HttpHeaders.Authorization] }
        assertEquals(tokens[0], tokens[1])
        assertEquals(2, tokens.toSet().size)
    }

    @Test
    fun `a device the app is gone from is reported as unregistered`() = testApplication {
        val sender = sender()
        answers += HttpStatusCode.Gone to """{"reason": "Unregistered", "timestamp": 1760000000000}"""
        answers += HttpStatusCode.BadRequest to """{"reason": "BadDeviceToken"}"""

        repeat(2) { assertEquals(PushSender.Result.Unregistered, sender.send(device, notification)) }
    }

    @Test
    fun `a busy apns is worth another attempt, a rejected push is not`() = testApplication {
        val sender = sender()
        answers += HttpStatusCode.TooManyRequests to """{"reason": "TooManyRequests"}"""
        answers += HttpStatusCode.ServiceUnavailable to """{"reason": "ServiceUnavailable"}"""
        answers += HttpStatusCode.BadRequest to """{"reason": "PayloadTooLarge"}"""

        repeat(2) { assertEquals(true, assertIs<PushSender.Result.Failed>(sender.send(device, notification)).isRetryable) }
        val rejected = assertIs<PushSender.Result.Failed>(sender.send(device, notification))
        assertEquals(false, rejected.isRetryable)
        assertEquals("400 PayloadTooLarge", rejected.reason)
    }

    @Test
    fun `a refused provider token is replaced on the next attempt`() = testApplication {
        val sender = sender()
        answers += HttpStatusCode.Forbidden to """{"reason": "ExpiredProviderToken"}"""

        assertEquals(true, assertIs<PushSender.Result.Failed>(sender.send(device, notification)).isRetryable)
        now += 1.minutes
        assertEquals(PushSender.Result.Sent, sender.send(device, notification))

        assertEquals(2, requests.map { it.headers[HttpHeaders.Authorization] }.toSet().size)
    }

    private fun ApplicationTestBuilder.sender(): ApnsPushSender {
        externalServices {
            listOf("sandbox", "production").forEach { host ->
                hosts("https://$host.apns.test") {
                    routing {
                        post("/3/device/{token}") {
                            requests += Request(
                                host = host,
                                path = call.request.local.uri,
                                headers = listOf(HttpHeaders.Authorization, "apns-topic", "apns-push-type", "apns-priority")
                                    .associateWith { call.request.headers[it] },
                                body = call.receiveText(),
                            )
                            val (status, body) = answers.removeFirstOrNull() ?: (HttpStatusCode.OK to "")
                            call.respondText(body, ContentType.Application.Json, status)
                        }
                    }
                }
            }
        }
        return ApnsPushSender(
            key = key,
            client = createClient { },
            urls = { "https://${it.name.lowercase()}.apns.test" },
            clock = clock,
        )
    }
}
