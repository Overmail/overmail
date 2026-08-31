package es.jvbabi.overmail.server.data.avatar

import es.jvbabi.overmail.server.data.avatar.resolver.BimiResolver
import es.jvbabi.overmail.server.data.avatar.resolver.ProvidedResolver
import es.jvbabi.overmail.server.util.maskEmail
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * Where a picture for a mail address is looked for, out on the network.
 *
 * Asks every resolver in turn and takes the first answer, so the order below is the priority: the
 * hand-kept list comes first because that is what it is for -- a sender whose published logo is
 * generic or wrong is fixed by putting the right url in there.
 */
class AvatarLookup {

    private val logger = LoggerFactory.getLogger(AvatarLookup::class.java)

    private val client = HttpClient(CIO) {
        followRedirects = true

        // The queue walks a whole address book, so one unresponsive host must not hold a slot.
        install(HttpTimeout) {
            requestTimeoutMillis = 5000
        }
    }

    private val resolvers = listOf(
        ProvidedResolver(client),
        BimiResolver(client),
    )

    /** @return the first picture any resolver had for [address], or null when none had one. */
    suspend fun findAvatarOnline(address: String, name: String? = null): Result? {
        for (resolver in resolvers) {
            val bytes = try {
                resolver.handle(address, name)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (cause: Exception) {
                // One resolver falling over is not the address's fault: a timeout on BIMI must not
                // cost the sender the logo the next resolver has for it.
                logger.debug(
                    "Resolver ${resolver.identifier} failed for ${address.maskEmail()}: ${cause.message}"
                )
                null
            }

            if (bytes != null) return Result(resolver.identifier, bytes)
        }

        return null
    }

    /** A picture as a resolver handed it over, together with which resolver that was. */
    class Result(
        val source: String,
        val data: ByteArray,
    )
}
