package es.jvbabi.overmail.server.jobs.push

import es.jvbabi.overmail.server.jobs.push.ApplePushSealing.toX963
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.interfaces.ECPublicKey
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** What a push to iOS looks like, and that only the device reads what it shows. */
class ApplePushPayloadsTest {

    private val keys = deviceKeys()
    private val device = iPhone("ab".repeat(32), keys).applePush!!
    private val userId = Uuid.random()
    private val newEmail = PushMessage.NewEmail(emailId = Uuid.random(), imapAccountId = Uuid.random())
    private val content = ApplePushContent(sender = "Ada Lovelace", subject = "Engines", preview = "About the analytical one")

    @Test
    fun `a new mail is a notification with a placeholder, its ids, and the content sealed`() {
        val payload = build(newEmail, content)

        val aps = payload.getValue("aps").jsonObject
        assertEquals("PUSH_NEW_EMAIL_TITLE", aps.getValue("alert").jsonObject.getValue("title-loc-key").jsonPrimitive.content)
        assertEquals("1", aps.getValue("mutable-content").jsonPrimitive.content)
        assertEquals(newEmail.imapAccountId.toString(), aps.getValue("thread-id").jsonPrimitive.content)
        assertEquals(setOf("aps", "user_id", "payload", "content"), payload.keys)
        assertEquals(userId.toString(), payload.getValue("user_id").jsonPrimitive.content)

        val opened = openSealed(Base64.getDecoder().decode(payload.getValue("content").jsonPrimitive.content), keys)
        assertEquals(ApplePushPayloads.CONTENT_SIZE, opened.size)
        assertEquals(content, Json.decodeFromString<ApplePushContent>(opened.decodeToString()))
    }

    @Test
    fun `nothing of the mail is in the clear`() {
        val payload = build(newEmail, content).toString()

        listOf("Ada", "Engines", "analytical").forEach { assertFalse(it in payload, "$it is readable") }
    }

    @Test
    fun `without content there is nothing to seal, and nothing for the extension to do`() {
        val payload = build(PushMessage.Ping, null)

        assertFalse("content" in payload)
        assertFalse("mutable-content" in payload.getValue("aps").jsonObject)
        assertEquals("PUSH_PING_TITLE", payload.getValue("aps").jsonObject.getValue("alert").jsonObject.getValue("title-loc-key").jsonPrimitive.content)
    }

    @Test
    fun `mails dealt with elsewhere are a background push that shows nothing`() {
        val message = PushMessage.EmailsChanged(listOf(Uuid.random()))
        val notification = ApplePushPayloads.build(message, userId, null, device)
        val aps = Json.parseToJsonElement(notification.payload).jsonObject.getValue("aps").jsonObject

        assertEquals(setOf("content-available"), aps.keys)
        assertFalse(notification.isVisible)
        assertFalse(notification.isUrgent)
    }

    @Test
    fun `every content is as long as the next`() {
        val short = ApplePushPayloads.padded(ApplePushContent(sender = "A"))
        val long = ApplePushPayloads.padded(content)

        assertEquals(short.size, long.size)
    }

    @Test
    fun `what does not fit is cut from the preview first, and the push stays inside what APNs takes`() {
        val huge = ApplePushContent(sender = "Ada", subject = "Engines", preview = "Ü\"".repeat(2_000))

        val padded = ApplePushPayloads.padded(huge)
        val fitted = Json.decodeFromString<ApplePushContent>(padded.decodeToString())

        assertEquals(ApplePushPayloads.CONTENT_SIZE, padded.size)
        assertEquals("Engines", fitted.subject)
        assertTrue(fitted.preview!!.endsWith("…"))
        assertTrue(build(newEmail, huge).toString().encodeToByteArray().size <= 4096)
    }

    @Test
    fun `a subject made only of characters outside the basic plane is cut between characters`() {
        val padded = ApplePushPayloads.padded(ApplePushContent(sender = "Ada", subject = "😀".repeat(2_000)))
        val fitted = Json.decodeFromString<ApplePushContent>(padded.decodeToString())

        assertNull(fitted.preview)
        assertTrue(fitted.subject!!.removeSuffix("…").chunked(2).all { it == "😀" })
    }

    @Test
    fun `each push is sealed anew`() {
        val first = build(newEmail, content).getValue("content")
        val second = build(newEmail, content).getValue("content")

        assertNotEquals(first, second)
    }

    @Test
    fun `a key that is no point on the curve is refused`() {
        val point = (keys.public as ECPublicKey).toX963()
        val broken = point.copyOf().also { it[64] = (it[64] + 1).toByte() }

        assertContentEquals(point, (ApplePushSealing.publicKeyOf(point)).toX963())
        assertFailsWith<IllegalArgumentException> { ApplePushSealing.publicKeyOf(broken) }
        assertFailsWith<IllegalArgumentException> { ApplePushSealing.publicKeyOf(point.copyOf(33)) }
    }

    private fun build(message: PushMessage, content: ApplePushContent?): JsonObject =
        Json.parseToJsonElement(ApplePushPayloads.build(message, userId, content, device).payload).jsonObject
}
