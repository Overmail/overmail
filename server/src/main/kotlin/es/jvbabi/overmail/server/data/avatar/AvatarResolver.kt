package es.jvbabi.overmail.server.data.avatar

import io.ktor.client.HttpClient

/**
 * One way of finding a picture for a mail address. Asked in order, first answer wins, see
 * [AvatarLookup].
 */
abstract class AvatarResolver(protected val client: HttpClient) {

    /** Recorded with the picture, so it is visible where one came from. */
    abstract val identifier: String

    /** @return the picture, or null when this resolver has nothing for [address]. */
    abstract suspend fun handle(address: String, name: String?): ByteArray?
}
