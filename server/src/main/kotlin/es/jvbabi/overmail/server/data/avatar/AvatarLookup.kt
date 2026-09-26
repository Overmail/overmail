package es.jvbabi.overmail.server.data.avatar

import es.jvbabi.overmail.server.data.avatar.resolver.BimiResolver
import es.jvbabi.overmail.server.data.avatar.resolver.ProvidedResolver
import es.jvbabi.overmail.server.util.maskEmail
import es.jvbabi.overmail.server.util.withSvgNamespace
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    /**
     * @return the first picture any resolver had for [address], as a png (see [toAvatarPng]), or
     *   null when none had one that could be decoded.
     */
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

            if (bytes == null) continue

            // Repaired and converted here rather than in each resolver: every picture comes off
            // somebody else's web server, and a namespace-less svg is unusable whichever one
            // served it. Decoding is processor work, so it leaves the IO dispatcher for it.
            val png = withContext(Dispatchers.Default) { bytes.withSvgNamespace().toAvatarPng() }

            // A picture nothing can decode is no answer, so the next resolver gets its turn.
            if (png == null) {
                logger.debug("Resolver ${resolver.identifier} had an undecodable picture for ${address.maskEmail()}")
                continue
            }

            return Result(resolver.identifier, png)
        }

        return null
    }

    /** A picture, converted to png, together with the resolver that found it. */
    class Result(
        val source: String,
        val data: ByteArray,
    )
}
