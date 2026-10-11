package es.jvbabi.overmail.server.jobs.push

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import es.jvbabi.overmail.server.config.FirebaseServiceAccount
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** What goes to Google, and what its answers are taken to mean. Google itself is a local route. */
class FcmPushSenderTest {

    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private val account = FirebaseServiceAccount(
        projectId = "overmail-test",
        clientEmail = "push@overmail-test.iam.gserviceaccount.com",
        privateKey = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keys.private.encoded) +
            "\n-----END PRIVATE KEY-----\n",
        tokenUri = "https://oauth2.test/token",
    )

    /** What the token endpoint was asked, and what the send endpoint received. */
    private val assertions = mutableListOf<String>()
    private val sent = mutableListOf<Pair<String?, String>>()

    /** The answers of the send endpoint, in order; 200 once they are used up. */
    private val answers = ArrayDeque<Pair<HttpStatusCode, String>>()

    private var now = Instant.parse("2026-10-10T12:00:00Z")
    private val clock = object : Clock {
        override fun now() = now
    }

    @Test
    fun `a push is one data message, signed in as the service account`() = testApplication {
        val sender = sender()

        val result = sender.send("device-token", mapOf("user_id" to "u", "payload" to "{}"), isUrgent = true)

        assertEquals(PushSender.Result.Sent, result)
        val (authorization, body) = sent.single()
        assertEquals("Bearer access-1", authorization)
        val message = Json.parseToJsonElement(body).jsonObject.getValue("message").jsonObject
        assertEquals(setOf("token", "data", "android"), message.keys)
        assertEquals("device-token", message.getValue("token").jsonPrimitive.content)
        assertEquals("{}", message.getValue("data").jsonObject.getValue("payload").jsonPrimitive.content)
        assertEquals("HIGH", message.getValue("android").jsonObject.getValue("priority").jsonPrimitive.content)

        // The request for the access token is what proves who is asking.
        // Its signature alone: the times in it are the test's, not the real clock's.
        val assertion = JWT.decode(assertions.single())
        Algorithm.RSA256(keys.public as RSAPublicKey, null).verify(assertion)
        assertEquals(account.clientEmail, assertion.issuer)
        assertEquals(listOf(account.tokenUri), assertion.audience)
        assertEquals("https://www.googleapis.com/auth/firebase.messaging", assertion.getClaim("scope").asString())
    }

    @Test
    fun `a push that need not wake the device says so`() = testApplication {
        sender().send("device-token", emptyMap(), isUrgent = false)

        val message = Json.parseToJsonElement(sent.single().second).jsonObject.getValue("message").jsonObject
        assertEquals("NORMAL", message.getValue("android").jsonObject.getValue("priority").jsonPrimitive.content)
    }

    @Test
    fun `the access token is asked for once and again when it runs out`() = testApplication {
        val sender = sender()

        sender.send("device-token", emptyMap(), isUrgent = true)
        sender.send("device-token", emptyMap(), isUrgent = true)
        assertEquals(1, assertions.size)

        now += 59.minutes + 30.minutes
        sender.send("device-token", emptyMap(), isUrgent = true)

        assertEquals(2, assertions.size)
        assertEquals(listOf("Bearer access-1", "Bearer access-1", "Bearer access-2"), sent.map { it.first })
    }

    @Test
    fun `a token firebase no longer knows is reported as that`() = testApplication {
        answers += HttpStatusCode.NotFound to """
            {"error": {"code": 404, "message": "Requested entity was not found.", "status": "NOT_FOUND",
             "details": [{"@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError", "errorCode": "UNREGISTERED"}]}}
        """

        assertEquals(PushSender.Result.Unregistered, sender().send("device-token", emptyMap(), isUrgent = true))
    }

    @Test
    fun `a busy or failing firebase is worth another attempt`() = testApplication {
        val sender = sender()
        answers += HttpStatusCode.ServiceUnavailable to """{"error": {"status": "UNAVAILABLE", "message": "Try again"}}"""
        answers += HttpStatusCode.TooManyRequests to """{"error": {"status": "RESOURCE_EXHAUSTED"}}"""

        repeat(2) {
            val result = assertIs<PushSender.Result.Failed>(sender.send("device-token", emptyMap(), isUrgent = true))
            assertEquals(true, result.isRetryable)
        }
    }

    @Test
    fun `a request firebase rejects is not`() = testApplication {
        answers += HttpStatusCode.BadRequest to """{"error": {"status": "INVALID_ARGUMENT", "message": "Invalid data"}}"""

        val result = assertIs<PushSender.Result.Failed>(sender().send("device-token", emptyMap(), isUrgent = true))

        assertEquals(false, result.isRetryable)
        assertEquals("400 INVALID_ARGUMENT: Invalid data", result.reason)
    }

    @Test
    fun `a refused access token is replaced on the next attempt`() = testApplication {
        val sender = sender()
        answers += HttpStatusCode.Unauthorized to """{"error": {"status": "UNAUTHENTICATED"}}"""

        val refused = assertIs<PushSender.Result.Failed>(sender.send("device-token", emptyMap(), isUrgent = true))
        assertEquals(true, refused.isRetryable)

        assertEquals(PushSender.Result.Sent, sender.send("device-token", emptyMap(), isUrgent = true))
        assertEquals(listOf("Bearer access-1", "Bearer access-2"), sent.map { it.first })
    }

    @Test
    fun `google refusing the service account is a failure, not an exception`() = testApplication {
        val sender = sender(tokenStatus = HttpStatusCode.BadRequest)

        val result = assertIs<PushSender.Result.Failed>(sender.send("device-token", emptyMap(), isUrgent = true))

        assertEquals(true, result.isRetryable)
        assertEquals(emptyList(), sent)
    }

    private fun ApplicationTestBuilder.sender(tokenStatus: HttpStatusCode = HttpStatusCode.OK): FcmPushSender {
        externalServices {
            hosts("https://oauth2.test") {
                routing {
                    post("/token") {
                        val form = call.receiveParameters()
                        assertEquals("urn:ietf:params:oauth:grant-type:jwt-bearer", form["grant_type"])
                        assertions += form.getValue("assertion")
                        call.respondText(
                            """{"access_token": "access-${assertions.size}", "expires_in": 3600, "token_type": "Bearer"}""",
                            ContentType.Application.Json,
                            tokenStatus,
                        )
                    }
                }
            }
            hosts("https://fcm.test") {
                routing {
                    post("/v1/projects/overmail-test/messages:send") {
                        sent += call.request.headers[HttpHeaders.Authorization] to call.receiveText()
                        val (status, body) = answers.removeFirstOrNull() ?: (HttpStatusCode.OK to """{"name": "projects/overmail-test/messages/1"}""")
                        call.respondText(body, ContentType.Application.Json, status)
                    }
                }
            }
        }
        return FcmPushSender(account, client = createClient { }, fcmUrl = "https://fcm.test", clock = clock)
    }
}

private fun io.ktor.http.Parameters.getValue(name: String): String = checkNotNull(this[name]) { "No $name in the form" }
