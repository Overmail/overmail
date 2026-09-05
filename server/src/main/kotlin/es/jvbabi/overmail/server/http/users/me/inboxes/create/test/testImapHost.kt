package es.jvbabi.overmail.server.http.users.me.inboxes.create.test

import es.jvbabi.overmail.core.SocketInstance
import es.jvbabi.overmail.server.http.api.invalidRequest
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUser
import io.ktor.http.HttpStatusCode
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.SocketTimeoutException
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.network.tls.TLSException
import io.ktor.network.tls.tls
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
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
    try {
        withTimeout(PROBE_TIMEOUT) {
            val socket = connectTls(selectorManager, host, port)
            SocketInstance(
                socket = socket,
                input = socket.openReadChannel(),
                output = socket.openWriteChannel(autoFlush = true),
                isDebug = false,
            ).use { connection ->
                connection.isReady.await()
                ImapHostTestResponse(
                    reachable = true,
                    outcome = ImapHostTestOutcome.REACHABLE.wire,
                    capabilities = readImapCapabilities(connection),
                )
            }
        }
    } catch (_: TimeoutCancellationException) {
        ImapHostTestResponse(false, ImapHostTestOutcome.TIMEOUT.wire)
    } catch (_: SocketTimeoutException) {
        ImapHostTestResponse(false, ImapHostTestOutcome.TIMEOUT.wire)
    } catch (_: UnknownHostException) {
        ImapHostTestResponse(false, ImapHostTestOutcome.HOST_NOT_FOUND.wire)
    } catch (_: ConnectException) {
        ImapHostTestResponse(false, ImapHostTestOutcome.CONNECTION_REFUSED.wire)
    } catch (_: TLSException) {
        ImapHostTestResponse(false, ImapHostTestOutcome.TLS_FAILED.wire)
    } catch (_: IOException) {
        // The port answered and the handshake held, but no `* OK` came out of it -- some other
        // service, or an imap server that greeted with `* BYE` and hung up.
        ImapHostTestResponse(false, ImapHostTestOutcome.NO_IMAP_SERVER.wire)
    } finally {
        selectorManager.close()
    }
}

/**
 * The tcp connection with tls on top, the way the importer opens one.
 *
 * The socket is closed here when the handshake is what fails: it is live from `connect` on, and
 * `tls` throwing leaves nothing else holding it.
 */
private suspend fun connectTls(selectorManager: SelectorManager, host: String, port: Int): Socket {
    val socket = aSocket(selectorManager).tcp().connect(host, port)
    return try {
        socket.tls(coroutineContext)
    } catch (e: Throwable) {
        socket.close()
        throw e
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
} catch (_: Exception) {
    emptyList()
}

/** The values [ImapHostTestResponse.outcome] can carry. A client branches on these, so they are short. */
internal enum class ImapHostTestOutcome(val wire: String) {
    /** Tls held and the server greeted with `* OK`. */
    REACHABLE("reachable"),

    /** The host does not resolve -- a typo in the domain. */
    HOST_NOT_FOUND("host_not_found"),

    /** The host resolves, but nothing listens on that port -- usually the wrong port. */
    CONNECTION_REFUSED("connection_refused"),

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
