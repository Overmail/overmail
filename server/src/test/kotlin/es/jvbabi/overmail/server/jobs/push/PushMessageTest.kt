package es.jvbabi.overmail.server.jobs.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.uuid.Uuid

/** What a push looks like on the wire: the app reads exactly these names. */
class PushMessageTest {

    private val userId = Uuid.random()

    @Test
    fun `a new mail names the mail and its mailbox, and nothing of what it says`() {
        val emailId = Uuid.random()
        val imapAccountId = Uuid.random()

        val data = PushMessage.NewEmail(emailId, imapAccountId).toData(userId)

        assertEquals(
            mapOf(
                "user_id" to userId.toString(),
                "payload" to """{"type":"new_email","email_id":"$emailId","imap_account_id":"$imapAccountId"}""",
            ),
            data,
        )
    }

    @Test
    fun `a ping is its type alone`() {
        assertEquals(mapOf("user_id" to userId.toString(), "payload" to """{"type":"ping"}"""), PushMessage.Ping.toData(userId))
    }

    @Test
    fun `changed mails are named by their ids alone`() {
        val first = Uuid.random()
        val second = Uuid.random()

        val data = PushMessage.EmailsChanged(listOf(first, second)).toData(userId)

        assertEquals(
            mapOf(
                "user_id" to userId.toString(),
                "payload" to """{"type":"emails_changed","email_ids":["$first","$second"]}""",
            ),
            data,
        )
    }

    @Test
    fun `changed mails do not wake a sleeping device`() {
        assertFalse(PushMessage.EmailsChanged(listOf(Uuid.random())).isUrgent)
    }
}
