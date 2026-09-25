package es.jvbabi.overmail.server.http.avatar.item

import es.jvbabi.overmail.server.http.api.requireAvatarFromUrl
import es.jvbabi.overmail.server.util.imageContentType
import io.ktor.http.CacheControl
import io.ktor.server.auth.authenticate
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlin.time.Duration.Companion.days

/**
 * A year, and private on top: a picture is never rewritten in place, a refreshed one is a new id
 * and therefore a different url. Nothing a browser has under this one can go stale.
 */
private val AVATAR_CACHE_DURATION = 365.days

/**
 * The bytes of one avatar. Not scoped to the caller beyond being signed in: the id says nothing
 * about who corresponds with whom, and the same picture is shared by every address book entry that
 * resolved to it.
 */
fun Route.getAvatar() {
    authenticate {
        /**
         * Download the picture of a correspondent.
         *
         * Description: Cached privately for a year. A changed picture gets a new id, so the url of one never goes stale.
         *
         * Tag: Avatars
         *
         * Responses:
         *   - 200 image/png The picture, as png
         *   - 200 image/jpeg The picture, as jpeg
         *   - 200 image/gif The picture, as gif
         *   - 200 image/webp The picture, as webp
         *   - 200 image/svg+xml The picture, as svg
         *   - 404 [es.jvbabi.overmail.server.http.api.ApiErrorBody] No such picture
         */
        get {
            val image = call.requireAvatarFromUrl().data

            call.response.cacheControl(
                CacheControl.MaxAge(
                    maxAgeSeconds = AVATAR_CACHE_DURATION.inWholeSeconds.toInt(),
                    visibility = CacheControl.Visibility.Private,
                )
            )
            // The stored bytes carry no declared type, so it comes out of them, see imageContentType.
            call.respondBytes(image, image.imageContentType())
        }
    }
}
