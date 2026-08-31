package es.jvbabi.overmail.server.http.avatar.item

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.EmailAvatar
import es.jvbabi.overmail.server.util.imageContentType
import io.ktor.http.CacheControl
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

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
        get {
            val avatarId = call.parameters["avatarId"]?.let { Uuid.parseOrNull(it) }
                ?: return@get call.respond(HttpStatusCode.BadRequest)

            val database = call.application.dependencies.resolve<OvermailDatabase>()
            val image = database.query { EmailAvatar.findById(avatarId)?.data }
                ?: return@get call.respond(HttpStatusCode.NotFound)

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
