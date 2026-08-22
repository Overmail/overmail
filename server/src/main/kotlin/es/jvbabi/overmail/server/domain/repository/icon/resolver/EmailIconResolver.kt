package es.jvbabi.overmail.server.domain.repository.icon.resolver

import io.ktor.client.HttpClient

/**
 * One way of finding a picture for a mail address. Asked in order, first answer wins, see
 * [es.jvbabi.overmail.server.domain.repository.icon.EmailIconRepository].
 */
abstract class EmailIconResolver(protected val client: HttpClient) {

    /** Recorded with the picture, so it is visible where one came from. */
    abstract val identifier: String

    /** @return the picture, or null when this resolver has nothing for [address]. */
    abstract suspend fun handle(address: String, name: String?): ByteArray?
}
