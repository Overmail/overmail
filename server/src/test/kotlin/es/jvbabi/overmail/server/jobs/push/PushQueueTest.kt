package es.jvbabi.overmail.server.jobs.push

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Session
import es.jvbabi.overmail.server.database.models.Sessions
import es.jvbabi.overmail.server.database.models.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** Who a queued push reaches, and what a failing one costs. */
class PushQueueTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:push-queue-${Uuid.random()};DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private val sender = RecordingSender()
    private val message = PushMessage.Ping

    @Test
    fun `a push for a user reaches every device they are signed in on`() = runBlocking {
        val user = newUser()
        newSession(user, phone("pixel"))
        newSession(user, phone("tablet"))
        newSession(newUser(), phone("somebody-else"))

        send(PushTarget.User(user))

        assertEquals(setOf("pixel", "tablet"), sender.sent.map { it.token }.toSet())
    }

    @Test
    fun `a push for a session reaches that device alone`() = runBlocking {
        val user = newUser()
        val pixel = newSession(user, phone("pixel"))
        newSession(user, phone("tablet"))

        send(PushTarget.Session(pixel))

        assertEquals(listOf("pixel"), sender.sent.map { it.token })
    }

    @Test
    fun `a push for everyone reaches the devices of every user`() = runBlocking {
        newSession(newUser(), phone("pixel"))
        newSession(newUser(), phone("tablet"))

        send(PushTarget.Everyone)

        // The marker `send` waits for is somebody's device as well, and is not recorded.
        assertEquals(setOf("pixel", "tablet"), sender.sent.map { it.token }.toSet())
    }

    @Test
    fun `a session that cannot be pushed to is passed over`() = runBlocking {
        val user = newUser()
        newSession(user, Session.Client.Web(browser = "Firefox", device = "Mac", os = "macOS"))
        newSession(user, phone(token = null))
        val revoked = newSession(user, phone("revoked"))
        database.query { Sessions.update({ Sessions.id eq revoked }) { it[revokedAt] = Clock.System.now() } }
        newSession(user, phone("pixel"))

        send(PushTarget.User(user))

        assertEquals(listOf("pixel"), sender.sent.map { it.token })
    }

    @Test
    fun `a device with two sessions is sent to once`() = runBlocking {
        val user = newUser()
        newSession(user, phone("pixel"))
        newSession(user, phone("pixel"))

        send(PushTarget.User(user))

        assertEquals(listOf("pixel"), sender.sent.map { it.token })
    }

    @Test
    fun `the push carries its type and whose it is, and nothing else`() = runBlocking {
        val user = newUser()
        newSession(user, phone("pixel"))

        send(PushTarget.User(user))

        val push = sender.sent.single()
        assertEquals(setOf("user_id", "payload"), push.data.keys)
        assertEquals(user.toString(), push.data["user_id"])
        val payload = Json.parseToJsonElement(push.data.getValue("payload")).jsonObject
        assertEquals(setOf("type"), payload.keys)
        assertEquals("ping", payload.getValue("type").jsonPrimitive.content)
        assertEquals(true, push.isUrgent)
    }

    @Test
    fun `a push that fails for a reason that may pass is tried again`() = runBlocking {
        val user = newUser()
        newSession(user, phone("pixel"))
        sender.answers += listOf(retryable, retryable, PushSender.Result.Sent)

        send(PushTarget.User(user), retryDelays = listOf(Duration.ZERO, Duration.ZERO))

        assertEquals(3, sender.sent.size)
    }

    @Test
    fun `a push that keeps failing is given up on, and the next one still goes out`() = runBlocking {
        val user = newUser()
        newSession(user, phone("pixel"))
        sender.answers += listOf(retryable, retryable, retryable)

        send(PushTarget.User(user), retryDelays = listOf(Duration.ZERO, Duration.ZERO))
        assertEquals(3, sender.sent.size)

        send(PushTarget.User(user))
        assertEquals(4, sender.sent.size)
    }

    @Test
    fun `a push that cannot work is not tried again`() = runBlocking {
        val user = newUser()
        newSession(user, phone("pixel"))
        sender.answers += PushSender.Result.Failed("a broken payload", isRetryable = false)

        send(PushTarget.User(user), retryDelays = listOf(Duration.ZERO))

        assertEquals(1, sender.sent.size)
    }

    @Test
    fun `a token firebase no longer knows is forgotten`() = runBlocking {
        val user = newUser()
        val pixel = newSession(user, phone("pixel"))
        val tablet = newSession(user, phone("tablet"))
        sender.answerFor["pixel"] = PushSender.Result.Unregistered

        send(PushTarget.User(user))

        assertEquals(phone(token = null), clientOf(pixel))
        assertEquals(phone("tablet"), clientOf(tablet))
    }

    @Test
    fun `nothing is queued while push is switched off`() = runBlocking {
        val queue = PushQueue(database, PushSender.Disabled)
        val user = newUser()
        newSession(user, phone("pixel"))

        // Far more than the queue holds: none of them takes a place in it.
        repeat(5_000) { queue.enqueue(PushTarget.User(user), message) }
    }

    /** Queues one push and waits until the queue is through with it. */
    private suspend fun send(target: PushTarget, retryDelays: List<Duration> = emptyList()) {
        val queue = PushQueue(database, sender, retryDelays)
        val consumer = CoroutineScope(Dispatchers.Default).launch { queue.consume() }
        val before = sender.sent.size

        queue.enqueue(target, message)
        // A push nobody can receive is the only one that leaves no trace, so a marker follows it.
        val marker = newSession(newUser(), phone("marker-${Uuid.random()}"))
        queue.enqueue(PushTarget.Session(marker), message)
        withTimeout(10.seconds) { sender.markers.receive() }

        consumer.cancel()
        check(sender.sent.size >= before)
    }

    private fun phone(token: String?) =
        Session.Client.Android(device = "Pixel 8", manufacturer = "Google", os = "Android 15", firebaseToken = token)

    private suspend fun newUser(): Uuid {
        database.init()
        return database.query {
            User.new {
                username = "user-${Uuid.random()}"
                email = "user-${Uuid.random()}@example.com"
                firstname = "Julius"
                lastname = "Babies"
            }.id.value
        }
    }

    private suspend fun newSession(userId: Uuid, client: Session.Client): Uuid = database.query {
        Sessions.insertAndGetId {
            it[user] = userId
            it[Sessions.client] = client
            it[token] = "token-${Uuid.random()}"
        }.value
    }

    private suspend fun clientOf(sessionId: Uuid): Session.Client = database.query {
        Sessions.select(Sessions.client).where { Sessions.id eq sessionId }.single()[Sessions.client]
    }

    private val retryable = PushSender.Result.Failed("firebase is busy", isRetryable = true)
}

/** Records what was sent, and answers what the test lined up. Markers are reported, not recorded. */
private class RecordingSender : PushSender {
    data class Push(val token: String, val data: Map<String, String>, val isUrgent: Boolean)

    val sent = mutableListOf<Push>()
    val markers = Channel<Unit>(Channel.UNLIMITED)

    /** Answers for the next sends, in order; [PushSender.Result.Sent] once they are used up. */
    val answers = ArrayDeque<PushSender.Result>()
    val answerFor = mutableMapOf<String, PushSender.Result>()

    override suspend fun send(token: String, data: Map<String, String>, isUrgent: Boolean): PushSender.Result {
        if (token.startsWith("marker-")) {
            markers.send(Unit)
            return PushSender.Result.Sent
        }
        sent += Push(token, data, isUrgent)
        return answerFor[token] ?: answers.removeFirstOrNull() ?: PushSender.Result.Sent
    }
}
