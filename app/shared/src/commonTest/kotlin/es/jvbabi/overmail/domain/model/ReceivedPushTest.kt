package es.jvbabi.overmail.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class ReceivedPushTest {

    private val userId = Uuid.random()

    @Test
    fun `a ping is read with whose it is`() {
        val push = ReceivedPush.fromData(mapOf("user_id" to userId.toString(), "payload" to """{"type":"ping"}"""))

        assertEquals(ReceivedPush(userId, PushMessage.Ping), push)
    }

    @Test
    fun `a field this version does not know is ignored`() {
        val push = ReceivedPush.fromData(mapOf("user_id" to userId.toString(), "payload" to """{"type":"ping","sent_at":1}"""))

        assertEquals(PushMessage.Ping, push?.message)
    }

    @Test
    fun `a type this version does not know is passed over`() {
        assertNull(ReceivedPush.fromData(mapOf("user_id" to userId.toString(), "payload" to """{"type":"from_the_future"}""")))
    }

    @Test
    fun `a push that is not ours is passed over`() {
        assertNull(ReceivedPush.fromData(emptyMap()))
        assertNull(ReceivedPush.fromData(mapOf("user_id" to userId.toString())))
        assertNull(ReceivedPush.fromData(mapOf("user_id" to "nobody", "payload" to """{"type":"ping"}""")))
        assertNull(ReceivedPush.fromData(mapOf("user_id" to userId.toString(), "payload" to "not json")))
    }
}
