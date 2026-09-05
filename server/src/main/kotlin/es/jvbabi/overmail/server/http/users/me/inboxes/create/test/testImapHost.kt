package es.jvbabi.overmail.server.http.users.me.inboxes.create.test

import es.jvbabi.overmail.core.SocketInstance
import es.jvbabi.overmail.server.http.api.invalidRequest
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUser
import io.ktor.http.HttpStatusCode
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.network.tls.tls
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.io.IOException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Short on purpose: this answers a form field somebody is still typing in, so it has to come back
 * while the dialog is open. A mail server that needs longer than this to say hello is not one the
 * importer would get through a poll cycle with either.
 */
private val PROBE_TIMEOUT = 5.seconds

/**
 * Is there an imap server at this host and port: `POST /api/users/me/inboxes/create/test/imap-host`.
 *
 * Host and port are the first two fields of "new inbox", and they are the pair that can be wrong
 * on their own -- a typo in the host or the plaintext port 143 instead of 993 fails no matter which
 * credentials follow. This says so before the user has typed any, which is why it takes no
 * username and no password and never logs in.
 *
 * The probe is the tls handshake plus the server greeting: an imap server opens with `* OK`, and
 * anything that answers on the port without saying that is not one. [ImapHostTestResponse.outcome]
 * names which of the two failed, so the dialog can point at the field that is wrong instead of
 * saying "no connection". Never an error status -- an unreachable host is the answer to this
 * question, not a failed request.
 */
fun Route.testImapHost() {
    authenticate {
        post {
            call.requireAuthenticatedUser()
            val request = call.receive<ImapHostTestRequest>()

            val host = request.host.trim()
            if (host.isEmpty()) invalidRequest("host", "an imap server needs a host")
            if (request.port !in 1..65535) invalidRequest("port", "is not a port", request.port.toString())

            call.respond(HttpStatusCode.OK, probeImapHost(host, request.port))
        }
    }
}

/**
 * Opens a tls connection to [host]:[port] and waits for the imap greeting, then hangs up.
 *
 * Kamel's `ImapClient` cannot do this: every connection it hands out is logged in by its pool
 * factory, and there are no credentials yet at this point of the dialog. Guessing some would also
 * mean a failed login attempt against a stranger's server on every keystroke pause, which is what
 * gets an address rate-limited. So the connection is opened with [SocketInstance] directly, which
 * is the piece of kamel below the login: it completes `isReady` on the `* OK` the server opens
 * with, and that greeting is the whole test.
 *
 * Answers rather than throws -- every outcome here is a normal answer to "is this a mail server".
 */
internal suspend fun probeImapHost(host: String, port: Int): ImapHostTestResponse = withContext(Dispatchers.IO) {
    val selectorManager = SelectorManager(Dispatchers.IO)
    // The handshake and the encrypted stream over it run in coroutines of the context `tls` is
    // handed, and a broken handshake is one of them failing. Two reasons it gets a job of its own,
    // and specifically a *supervisor*: as a child of this one it would cancel the probe along with
    // itself, so no answer could be returned at all; and under a plain `Job` its failure would take
    // down the sibling coroutines `tls` is waiting on, which hangs the call instead of throwing
    // out of it. It stays alive until the socket is done with, hence the `finally`.
    val connectionJob = SupervisorJob()
    val phase = ImapProbePhase()
    try {
        withTimeout(PROBE_TIMEOUT) { openAndGreet(selectorManager, connectionJob, phase, host, port) }
    } catch (_: TimeoutCancellationException) {
        // Which step ran out of time is the useful part. Sitting in the handshake is the plaintext
        // port 143 of a server that, unlike a refusal, simply never answers a `ClientHello`.
        val outcome =
            if (phase.reached == ImapProbePhase.HANDSHAKE) ImapHostTestOutcome.TLS_FAILED
            else ImapHostTestOutcome.TIMEOUT
        ImapHostTestResponse(false, outcome.wire)
    } finally {
        connectionJob.cancel()
        selectorManager.close()
    }
}

/**
 * The probe itself, in the three steps it can fail in: resolve and connect, hand shake, greet.
 *
 * Which step failed is what says which outcome it is -- not the exception type. Ktor reports a
 * broken handshake as whatever the bytes it choked on suggest (`TLSException` for a rejected
 * certificate, a bare `IllegalArgumentException: Invalid TLS record type` for the plaintext port
 * 143, an `EOFException` for a server that just hangs up), and the one thing they have in common
 * is that the tcp connection before them was already up.
 */
private suspend fun openAndGreet(
    selectorManager: SelectorManager,
    connectionJob: CompletableJob,
    phase: ImapProbePhase,
    host: String,
    port: Int,
): ImapHostTestResponse {
    val tcpSocket = try {
        aSocket(selectorManager).tcp().connect(host, port)
    } catch (_: UnknownHostException) {
        return ImapHostTestResponse(false, ImapHostTestOutcome.HOST_NOT_FOUND.wire)
    } catch (_: UnresolvedAddressException) {
        // What the nio connect below ktor actually throws for a name that does not resolve, and it
        // is an IllegalArgumentException -- neither an IOException nor caught by the clause above.
        return ImapHostTestResponse(false, ImapHostTestOutcome.HOST_NOT_FOUND.wire)
    } catch (_: IOException) {
        // Refused, no route, unreachable network -- from a form's point of view all the same:
        // the name resolved and nothing came up on that port.
        return ImapHostTestResponse(false, ImapHostTestOutcome.CONNECTION_FAILED.wire)
    }

    phase.reached = ImapProbePhase.HANDSHAKE
    // The handler matters as much as the supervisor: a supervisor propagates a child's failure
    // nowhere, so without one it reaches the thread's default handler, which prints it and --
    // under the test dispatcher -- fails whichever test runs next. A broken handshake is an
    // expected answer here, not an error to report.
    val socket = try {
        tcpSocket.tls(Dispatchers.IO + connectionJob + CoroutineExceptionHandler { _, _ -> })
    } catch (e: CancellationException) {
        // The probe timeout, which is not the handshake's fault.
        tcpSocket.close()
        throw e
    } catch (_: Exception) {
        tcpSocket.close()
        return ImapHostTestResponse(false, ImapHostTestOutcome.TLS_FAILED.wire)
    }

    phase.reached = ImapProbePhase.GREETING
    return SocketInstance(
        socket = socket,
        input = socket.openReadChannel(),
        output = socket.openWriteChannel(autoFlush = true),
        isDebug = false,
    ).use { connection ->
        try {
            connection.isReady.await()
            ImapHostTestResponse(
                reachable = true,
                outcome = ImapHostTestOutcome.REACHABLE.wire,
                capabilities = readImapCapabilities(connection),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Tls held, but no `* OK` came out of it -- some other service behind that port.
            ImapHostTestResponse(false, ImapHostTestOutcome.NO_IMAP_SERVER.wire)
        }
    }
}

/**
 * How far [openAndGreet] got, so a probe that runs out of time can still say where.
 *
 * A holder rather than a return value: the timeout cancels [openAndGreet] mid-step, and a
 * cancelled call returns nothing.
 */
private class ImapProbePhase {
    var reached: Int = CONNECT

    companion object {
        /** Resolving the host and opening the tcp connection. */
        const val CONNECT = 0

        /** The tls handshake on top of it. */
        const val HANDSHAKE = 1

        /** Waiting for the `* OK` the server opens with. */
        const val GREETING = 2
    }
}

/**
 * What the server lists on `CAPABILITY`, e.g. `IMAP4rev1`, `AUTH=PLAIN`, `LOGINDISABLED`.
 *
 * The dialog shows this as the evidence that the thing at the other end really is a mail server,
 * and it is what tells apart a host that will refuse a password login later from one that will
 * take it. Empty rather than fatal when the command fails: the greeting already answered the
 * question this route was asked.
 */
private suspend fun readImapCapabilities(connection: SocketInstance): List<String> = try {
    val response = connection.execute("CAPABILITY")
    val capabilities = mutableListOf<String>()
    response.response.consumeEach { line ->
        if (!line.startsWith("* CAPABILITY", ignoreCase = true)) return@consumeEach
        capabilities += line.split(" ").drop(2).filter { it.isNotBlank() }
    }
    capabilities.distinct()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    emptyList()
}

/** The values [ImapHostTestResponse.outcome] can carry. A client branches on these, so they are short. */
internal enum class ImapHostTestOutcome(val wire: String) {
    /** Tls held and the server greeted with `* OK`. */
    REACHABLE("reachable"),

    /** The host does not resolve -- a typo in the domain. */
    HOST_NOT_FOUND("host_not_found"),

    /** The host resolves, but no connection came up on that port -- usually the wrong port. */
    CONNECTION_FAILED("connection_failed"),

    /** Something listens, but it does not speak tls -- usually the plaintext port 143. */
    TLS_FAILED("tls_failed"),

    /** Tls held, but no imap greeting came. */
    NO_IMAP_SERVER("no_imap_server"),

    /** Neither an answer nor a refusal within [PROBE_TIMEOUT] -- a firewall dropping the packets. */
    TIMEOUT("timeout"),
}

@Serializable
internal data class ImapHostTestRequest(
    @SerialName("host") val host: String,
    @SerialName("port") val port: Int,
)

@Serializable
internal data class ImapHostTestResponse(
    /** The one field a client that only wants a green tick reads. */
    @SerialName("reachable") val reachable: Boolean,
    /** Which outcome it was, see [ImapHostTestOutcome]. */
    @SerialName("outcome") val outcome: String,
    /** What the server answered `CAPABILITY` with; empty unless [reachable]. */
    @SerialName("capabilities") val capabilities: List<String> = emptyList(),
)
