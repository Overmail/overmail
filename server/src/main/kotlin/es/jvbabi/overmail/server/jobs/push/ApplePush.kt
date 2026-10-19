package es.jvbabi.overmail.server.jobs.push

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.EmailPreviews
import es.jvbabi.overmail.server.database.models.EmailUsers
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.Session
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import java.util.Base64
import kotlin.uuid.Uuid

/**
 * What the notification of a [PushMessage] shows on iOS, before it is sealed for the device.
 *
 * iOS has no counterpart to the data message the Android app turns into a notification itself: a
 * silent push is throttled, and dropped for an app that was swiped away. So a push to iOS is the
 * notification, with a placeholder text, and this travels next to it -- encrypted, see
 * [ApplePushSealing] -- for the notification service extension to put in its place.
 *
 * A null field is left to the extension's own text, which is translated.
 */
@Serializable
data class ApplePushContent(
    @SerialName("sender") val sender: String? = null,
    @SerialName("subject") val subject: String? = null,
    @SerialName("preview") val preview: String? = null,
) {
    companion object {
        /**
         * What [message] shows, read from the database. Null when it shows nothing of its own, or
         * what it was about is gone already.
         */
        suspend fun of(database: OvermailDatabase, message: PushMessage): ApplePushContent? = when (message) {
            is PushMessage.NewEmail -> database.query {
                Emails
                    .join(EmailUsers, JoinType.INNER, Emails.sender, EmailUsers.id)
                    .join(EmailPreviews, JoinType.LEFT, Emails.id, EmailPreviews.email)
                    .select(Emails.senderName, EmailUsers.address, Emails.subject, EmailPreviews.preview)
                    .where { Emails.id eq message.emailId }
                    .singleOrNull()
                    ?.let { row ->
                        ApplePushContent(
                            sender = row[Emails.senderName]?.takeIf { it.isNotBlank() } ?: row[EmailUsers.address],
                            subject = row[Emails.subject]?.takeIf { it.isNotBlank() },
                            preview = row.getOrNull(EmailPreviews.preview)?.takeIf { it.isNotBlank() },
                        )
                    }
            }
            PushMessage.Ping, is PushMessage.EmailsChanged -> null
        }
    }
}

/**
 * One push for APNs: the json it carries, whether it has to wake the device, and whether it shows
 * a notification or only wakes the app in the background.
 */
data class ApplePushNotification(val payload: String, val isUrgent: Boolean, val isVisible: Boolean = true)

/**
 * Builds what goes to APNs for a [PushMessage]: the notification with its placeholder, the ids the
 * app opens the mail by, and the [ApplePushContent] sealed for the device.
 */
object ApplePushPayloads {

    /**
     * What the content may take up before it is sealed, and what it is padded to: every push of a
     * kind is as long as the next, so its length says nothing about the subject. APNs takes 4 KB
     * in all, and the sealed content grows by its header and by base64.
     */
    const val CONTENT_SIZE = 2048

    /** Keys of the placeholder in the app's `Localizable.strings`, what shows if the extension fails. */
    private const val NEW_EMAIL_TITLE = "PUSH_NEW_EMAIL_TITLE"
    private const val NEW_EMAIL_BODY = "PUSH_NEW_EMAIL_BODY"
    private const val PING_TITLE = "PUSH_PING_TITLE"
    private const val PING_BODY = "PUSH_PING_BODY"

    const val CONTENT_KEY = "content"

    private val json = Json { encodeDefaults = false; explicitNulls = false }

    fun build(
        message: PushMessage,
        userId: Uuid,
        content: ApplePushContent?,
        device: Session.Client.ApplePushRegistration,
    ): ApplePushNotification {
        val sealed = content?.let {
            val publicKey = ApplePushSealing.publicKeyOf(Base64.getDecoder().decode(device.publicKey))
            Base64.getEncoder().encodeToString(ApplePushSealing.seal(padded(it), publicKey))
        }

        val isVisible = when (message) {
            is PushMessage.NewEmail, PushMessage.Ping -> true
            is PushMessage.EmailsChanged -> false
        }

        val payload = buildJsonObject {
            putJsonObject("aps") {
                when (message) {
                    is PushMessage.NewEmail -> {
                        putJsonObject("alert") {
                            put("title-loc-key", NEW_EMAIL_TITLE)
                            put("loc-key", NEW_EMAIL_BODY)
                        }
                        // Notifications of one mailbox are grouped, as the Android app does.
                        put("thread-id", message.imapAccountId.toString())
                    }
                    PushMessage.Ping -> putJsonObject("alert") {
                        put("title-loc-key", PING_TITLE)
                        put("loc-key", PING_BODY)
                    }
                    // A background push: iOS wakes the app when it sees fit, which is enough to
                    // take away notifications that are dealt with -- and costs nothing when it
                    // decides not to.
                    is PushMessage.EmailsChanged -> put("content-available", 1)
                }
                if (isVisible) put("sound", "default")
                // What wakes the extension; without content there is nothing for it to do.
                if (sealed != null) put("mutable-content", 1)
            }
            message.toData(userId).forEach { (key, value) -> put(key, value) }
            if (sealed != null) put(CONTENT_KEY, sealed)
        }

        return ApplePushNotification(payload = payload.toString(), isUrgent = message.isUrgent, isVisible = isVisible)
    }

    /**
     * [content] as json, padded with spaces to [CONTENT_SIZE] -- json allows any whitespace after
     * the value. What does not fit is cut from the end of the preview first, then the subject.
     */
    fun padded(content: ApplePushContent): ByteArray {
        var fitted = content
        while (true) {
            val encoded = json.encodeToString(ApplePushContent.serializer(), fitted).encodeToByteArray()
            val overflow = encoded.size - CONTENT_SIZE
            if (overflow <= 0) return encoded + ByteArray(-overflow) { ' '.code.toByte() }

            fitted = when {
                fitted.preview != null -> fitted.copy(preview = fitted.preview.shortenedBy(overflow))
                fitted.subject != null -> fitted.copy(subject = fitted.subject.shortenedBy(overflow))
                else -> fitted.copy(sender = fitted.sender?.shortenedBy(overflow))
            }
        }
    }

    /**
     * Shorter by about [bytes] of utf-8 and ending in an ellipsis, or null when nothing is left.
     * Cut by characters, at least one more each time, so it may take the caller a few rounds --
     * but never splits a character, and always ends.
     */
    private fun String.shortenedBy(bytes: Int): String? {
        val text = removeSuffix("…")
        val remaining = text.length - maxOf(1, bytes / 4)
        if (remaining <= 0) return null
        // Not between the halves of a surrogate pair.
        val end = if (Character.isHighSurrogate(text[remaining - 1])) remaining - 1 else remaining
        return text.take(end).takeIf { it.isNotEmpty() }?.plus("…")
    }
}
