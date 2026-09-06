package es.jvbabi.overmail.server.http.users.me.inboxes.create.test

import es.jvbabi.overmail.core.ImapClient
import es.jvbabi.overmail.core.ImapCommandException
import es.jvbabi.overmail.server.http.api.invalidRequest
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUser
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Longer than the host probe: this one connects *and* logs in, and a server that deliberately
 * slows a login down (most do, against guessing) still has to fit inside it.
 */
private val LOGIN_TIMEOUT = 15.seconds

/**
 * Do these credentials work: `POST /api/users/me/inboxes/create/test/imap-login`.
 *
 * The second step of "new inbox", and it is only reached once `test/imap-host` said the host
 * answers -- which is what lets this classify so much more coarsely than the host probe does.
 * Everything that is not "the server rejected the login" is the connection having gone away
 * between the two steps, and the dialog sends the user back a step for all of them alike.
 *
 * Nothing is stored. The credentials live for the length of this request; the inbox is created
 * later, from the form the user is still filling in.
 */
fun Route.testImapLogin() {
    authenticate {
        post {
            call.requireAuthenticatedUser()
            val request = call.receive<ImapLoginTestRequest>()

            val host = request.host.trim()
            if (host.isEmpty()) invalidRequest("host", "an imap server needs a host")
            if (request.port !in 1..65535) invalidRequest("port", "is not a port", request.port.toString())
            if (request.username.isEmpty()) invalidRequest("username", "a login needs a username")

            call.respond(HttpStatusCode.OK, probeImapLogin(host, request.port, request.username, request.password))
        }
    }
}

/**
 * Connects and logs in, then hangs up again.
 *
 * `ImapClient` is what does the work here, unlike in the host probe: its pool factory connects,
 * hand shakes and sends `LOGIN` in one go, which at this point is exactly the question being
 * asked. A `NO`/`BAD` on that login surfaces as [ImapCommandException] -- the server having read
 * the credentials and refused them, which is the one outcome worth telling apart.
 */
internal suspend fun probeImapLogin(
    host: String,
    port: Int,
    username: String,
    password: String,
): ImapLoginTestResponse = withContext(Dispatchers.IO) {
    val connections = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, _ -> })
    try {
        withTimeout(LOGIN_TIMEOUT) {
            // A scope of this probe's own rather than the one `ImapClient` makes for itself:
            // it is cancelled below whatever happens, so nothing kamel launched outlives the
            // request, and the handler keeps an expected connection failure out of the default
            // one -- see the same reasoning in `testImapHost.kt`.
            ImapClient(
                host = host,
                port = port,
                username = username,
                password = password,
                coroutineScope = connections,
                debug = false,
            ).use { client ->
                client.testConnection()
                ImapLoginTestResponse(true, ImapLoginTestOutcome.AUTHENTICATED.wire)
            }
        }
    } catch (_: TimeoutCancellationException) {
        ImapLoginTestResponse(false, ImapLoginTestOutcome.TIMEOUT.wire)
    } catch (_: ImapCommandException) {
        ImapLoginTestResponse(false, ImapLoginTestOutcome.INVALID_CREDENTIALS.wire)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        ImapLoginTestResponse(false, ImapLoginTestOutcome.CONNECTION_FAILED.wire)
    } finally {
        connections.cancel()
    }
}

/** The values [ImapLoginTestResponse.outcome] can carry. A client branches on these. */
internal enum class ImapLoginTestOutcome(val wire: String) {
    /** The server took the credentials. */
    AUTHENTICATED("authenticated"),

    /** The server read them and said no. */
    INVALID_CREDENTIALS("invalid_credentials"),

    /** The host answered a step ago and does not anymore. */
    CONNECTION_FAILED("connection_failed"),

    /** No answer within [LOGIN_TIMEOUT]. */
    TIMEOUT("timeout"),
}

@Serializable
internal data class ImapLoginTestRequest(
    @SerialName("host") val host: String,
    @SerialName("port") val port: Int,
    @SerialName("username") val username: String,
    @SerialName("password") val password: String,
)

@Serializable
internal data class ImapLoginTestResponse(
    /** The one field a client that only wants to enable its "next" button reads. */
    @SerialName("authenticated") val authenticated: Boolean,
    /** Which outcome it was, see [ImapLoginTestOutcome]. */
    @SerialName("outcome") val outcome: String,
)
