package es.jvbabi.overmail.server.domain.repository.icon

import es.jvbabi.overmail.server.domain.repository.icon.resolver.BimiResolver
import es.jvbabi.overmail.server.domain.repository.icon.resolver.ProvidedResolver
import es.jvbabi.overmail.server.util.maskEmail
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * Asks every resolver in turn and takes the first answer, so the order below is the priority: the
 * hand-kept list comes first because that is what it is for -- a sender whose published logo is
 * generic or wrong is fixed by putting the right url in there.
 */
class EmailIconRepositoryImpl : EmailIconRepository {

    private val logger = LoggerFactory.getLogger(EmailIconRepositoryImpl::class.java)

    private val client = HttpClient(CIO) {
        followRedirects = true

        // Refreshing walks a whole address book, so one unresponsive host must not hold a slot.
        install(HttpTimeout) {
            requestTimeoutMillis = 5000
        }

        install(ContentNegotiation) {
            json()
        }
    }

    private val resolvers = listOf(
        ProvidedResolver(client),
        BimiResolver(client),
    )

    override suspend fun findIconOnline(address: String, name: String?): IconResult? {
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

            if (bytes != null) return IconResult(resolver.identifier, bytes)
        }

        return null
    }
}
