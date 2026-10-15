package es.jvbabi.overmail.server.jobs.push

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Session
import es.jvbabi.overmail.server.database.models.Sessions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** How many pushes wait at most. One that does not fit is dropped: the app catches up when opened. */
private const val CAPACITY = 1_000

/**
 * Queue of pushes on their way out.
 *
 * [enqueue] never waits, so whoever has something to announce -- an import step, a route -- is
 * not held up by Firebase or APNs. [consume] resolves each target into the devices behind it and
 * hands the push to the [sender] of an Android device or the [appleSender] of an iOS one, once
 * per device.
 *
 * Kept in memory on purpose. A push only says "ask the server"; one lost in a restart costs a
 * notification, not a mail.
 *
 * @param retryDelays how long to wait before each further attempt at a push that failed for a
 *   reason that may pass. One consumer, so a long list here holds up everything behind it.
 */
class PushQueue(
    private val database: OvermailDatabase,
    private val sender: PushSender,
    private val appleSender: ApplePushSender = ApplePushSender.Disabled,
    private val retryDelays: List<Duration> = listOf(1.seconds, 5.seconds),
) {

    private val logger = LoggerFactory.getLogger(PushQueue::class.java)

    private data class Entry(val target: PushTarget, val message: PushMessage)

    /** One device a push goes to. */
    private data class Recipient(val sessionId: Uuid, val userId: Uuid, val device: Device)

    /** How a device is reached, and what addresses it. */
    private sealed class Device {
        abstract val token: String

        data class Firebase(override val token: String) : Device()

        data class Apple(val registration: Session.Client.ApplePushRegistration) : Device() {
            override val token get() = registration.token
        }
    }

    private val channel = Channel<Entry>(capacity = CAPACITY)

    /** Whether a push could go out at all: false while the server has neither Firebase nor APNs credentials. */
    val isEnabled: Boolean
        get() = sender !== PushSender.Disabled || appleSender !== ApplePushSender.Disabled

    /** Takes a push for [target]. Never suspends, never blocks a writer. */
    fun enqueue(target: PushTarget, message: PushMessage) {
        // Nothing would ever send it, so it is not worth a place in the queue.
        if (!isEnabled) return
        if (channel.trySend(Entry(target, message)).isFailure) {
            logger.warn("The push queue is full, dropping a push for $target")
        }
    }

    /** Works through the queue until it is closed. Suspends while it is empty. */
    suspend fun consume() {
        for ((target, message) in channel) {
            try {
                val recipients = recipientsOf(target)
                // Read once for every iOS device, and not at all without one.
                val appleContent = if (recipients.any { it.device is Device.Apple }) ApplePushContent.of(database, message) else null
                val delivered = recipients.count { deliver(it, message, appleContent) }
                if (recipients.isNotEmpty()) {
                    logger.info("Pushed ${message::class.simpleName} for $target to $delivered of ${recipients.size} device(s)")
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (cause: Exception) {
                logger.warn("Could not send a push to $target: ${cause.message}")
            }
        }
    }

    /** Whether the push was handed over. One that was not is logged here, or its token forgotten. */
    private suspend fun deliver(recipient: Recipient, message: PushMessage, appleContent: ApplePushContent?): Boolean {
        val send: suspend () -> PushSender.Result = when (val device = recipient.device) {
            is Device.Firebase -> {
                val data = message.toData(recipient.userId)
                ({ sender.send(device.token, data, message.isUrgent) })
            }
            is Device.Apple -> {
                // Sealed once, for this device's key; a key that does not read costs this device alone.
                val notification = try {
                    ApplePushPayloads.build(message, recipient.userId, appleContent, device.registration)
                } catch (cause: IllegalArgumentException) {
                    logger.warn("Cannot seal a push for session ${recipient.sessionId}: ${cause.message}")
                    return false
                }
                ({ appleSender.send(device.registration, notification) })
            }
        }

        var attempt = 0
        while (true) {
            when (val result = send()) {
                PushSender.Result.Sent -> return true
                PushSender.Result.Unregistered -> {
                    forgetToken(recipient)
                    return false
                }
                is PushSender.Result.Failed -> {
                    val wait = retryDelays.getOrNull(attempt++).takeIf { result.isRetryable }
                    if (wait == null) {
                        logger.warn("Giving up on a push to session ${recipient.sessionId}: ${result.reason}")
                        return false
                    }
                    delay(wait)
                }
            }
        }
    }

    /**
     * The devices behind [target]: its sessions that still work and have a token a configured
     * sender can use. A token is sent to once, even if two sessions of the same device hold it.
     */
    private suspend fun recipientsOf(target: PushTarget): List<Recipient> = database.query {
        Sessions
            .select(Sessions.id, Sessions.user, Sessions.client)
            .where {
                when (target) {
                    PushTarget.Everyone -> Sessions.revokedAt.isNull()
                    is PushTarget.User -> (Sessions.user eq target.userId) and Sessions.revokedAt.isNull()
                    is PushTarget.Session -> (Sessions.id eq target.sessionId) and Sessions.revokedAt.isNull()
                }
            }
            .mapNotNull { row ->
                val device = deviceOf(row[Sessions.client]) ?: return@mapNotNull null
                Recipient(sessionId = row[Sessions.id].value, userId = row[Sessions.user].value, device = device)
            }
            .distinctBy { it.device.token }
    }

    /** Whether a push to [client] goes out: it has a token, and the sender of its platform is not disabled. */
    fun reaches(client: Session.Client): Boolean = deviceOf(client) != null

    private fun deviceOf(client: Session.Client): Device? = when (client) {
        is Session.Client.Android -> client.firebaseInstallationId
            ?.takeIf { sender !== PushSender.Disabled }
            ?.let(Device::Firebase)
        is Session.Client.Ios -> client.applePush
            ?.takeIf { appleSender !== ApplePushSender.Disabled }
            ?.let(Device::Apple)
        is Session.Client.Web -> null
    }

    /** Drops a token Firebase or APNs no longer knows, so it is not sent to again. */
    private suspend fun forgetToken(recipient: Recipient) {
        database.query {
            val client = Sessions.select(Sessions.client).where { Sessions.id eq recipient.sessionId }.firstOrNull()?.get(Sessions.client)
            // Only the token that failed: the app may have registered a new one in the meantime.
            val withoutToken = when {
                client is Session.Client.Android && client.firebaseInstallationId == recipient.device.token ->
                    client.copy(firebaseInstallationId = null)
                client is Session.Client.Ios && client.applePush?.token == recipient.device.token ->
                    client.copy(applePush = null)
                else -> return@query
            }
            Sessions.update({ Sessions.id eq recipient.sessionId }) { it[Sessions.client] = withoutToken }
        }
        logger.info("Session ${recipient.sessionId} no longer has a push token")
    }
}
