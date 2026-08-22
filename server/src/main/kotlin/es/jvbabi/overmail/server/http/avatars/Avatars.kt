package es.jvbabi.overmail.server.http.avatars

import es.jvbabi.overmail.server.auth.SESSION_AUTH
import es.jvbabi.overmail.server.domain.models.User
import es.jvbabi.overmail.server.domain.repository.EmailAvatarRepository
import es.jvbabi.overmail.server.domain.repository.EmailUserRepository
import es.jvbabi.overmail.server.jobs.avatar.AvatarRefresher
import es.jvbabi.overmail.server.util.imageContentType
import io.ktor.http.CacheControl
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.resolve
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * A year, and immutable on top: a picture is never rewritten in place, a refreshed one is a new id
 * and therefore a different url. Nothing a browser has under this one can go stale.
 */
private const val AVATAR_CACHE_SECONDS = 31_536_000

/** The avatar cache: what we hold for the caller's correspondents, and filling it up. */
fun Route.avatars() {

    authenticate(SESSION_AUTH) {
        /**
         * Every picture the caller's address book points at, as a map from address to the url its
         * bytes sit behind, plus how much of the address book is covered.
         *
         * Read once by a client and then used for every mail in a list, which is why it is by
         * address rather than per mail: the same handful of senders fills a mailbox, and a listing
         * would otherwise repeat the same ids thousands of times. Addresses nothing was found for
         * are absent -- a client renders its own initials for those instead of asking for a
         * picture that cannot come.
         *
         * `refresh` reports the caller's last [AvatarRefresher] run, absent when they never asked
         * for one. While it is running this listing is worth reading again, because pictures land
         * in it as they are downloaded.
         */
        get {
            // Inside `authenticate` there is a user, or the request never got here.
            val user = call.principal<User>() ?: return@get call.respond(HttpStatusCode.Unauthorized)

            val dependencies = call.application.dependencies
            val avatars = dependencies.resolve<EmailAvatarRepository>().getForUser(user).first()
            val addresses = dependencies.resolve<EmailUserRepository>().distinctAddresses(user).first()
            val refresh = dependencies.resolve<AvatarRefresher>().progressOf(user)

            call.respond(
                AvatarListResponse(
                    avatars = avatars.map {
                        AvatarResponse(
                            address = it.address,
                            id = it.id.toString(),
                            source = it.source,
                            createdAt = it.createdAt.toString(),
                        )
                    },
                    addressesTotal = addresses.size,
                    refresh = refresh?.toResponse(),
                )
            )
        }

        /**
         * Downloads avatars for the caller's address book.
         *
         * `all=true` visits every address and throws away what is cached for them first; without
         * it only the addresses no picture was ever found for are visited, which leaves the
         * existing ids -- and everything browsers cached under those urls -- untouched.
         *
         * Answers before a single picture has been downloaded: a real address book takes minutes.
         * Watch `GET /api/avatars` for how far it got and for the pictures as they land. A second
         * request while one is still running answers `409 Conflict`.
         */
        post("/refresh") {
            val user = call.principal<User>() ?: return@post call.respond(HttpStatusCode.Unauthorized)

            // Defaults to the cheap one: a mistyped parameter should not throw away every picture.
            val all = call.parameters["all"].toBoolean()

            val started = call.application.dependencies.resolve<AvatarRefresher>().start(user, all)
                ?: return@post call.respond(HttpStatusCode.Conflict)

            call.respond(HttpStatusCode.Accepted, started.toResponse())
        }

        /**
         * The bytes of one avatar. Not scoped to the caller: the id says nothing about who
         * corresponds with whom, and the same picture is shared by every address book that
         * resolved to it.
         */
        get("/{id}") {
            call.principal<User>() ?: return@get call.respond(HttpStatusCode.Unauthorized)

            val id = call.parameters["id"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                ?: return@get call.respond(HttpStatusCode.BadRequest)

            val image = call.application.dependencies.resolve<EmailAvatarRepository>()
                .getImage(id)
                .first()
                ?: return@get call.respond(HttpStatusCode.NotFound)

            call.response.cacheControl(
                CacheControl.MaxAge(
                    AVATAR_CACHE_SECONDS,
                    visibility = CacheControl.Visibility.Private,
                )
            )
            // The stored bytes carry no declared type, so it comes out of them, see imageContentType.
            call.respondBytes(image, image.imageContentType())
        }
    }
}

private fun AvatarRefresher.Progress.toResponse() = RefreshResponse(
    running = running,
    all = all,
    total = total,
    done = done,
    found = found,
)

/** The avatar cache as `GET /api/avatars` reports it. */
@Serializable
data class AvatarListResponse(
    /** One entry per address a picture was found for; the others are simply absent. */
    @SerialName("avatars") val avatars: List<AvatarResponse>,
    /** Addresses in the caller's book altogether, so the covered share can be shown. */
    @SerialName("addresses_total") val addressesTotal: Int,
    /** The caller's last refresh, absent when they never started one. */
    @SerialName("refresh") val refresh: RefreshResponse? = null,
)

/** The picture found for one address. */
@Serializable
data class AvatarResponse(
    @SerialName("address") val address: String,
    /** Also the cache key of the url the bytes sit behind: `/api/avatars/{id}`. */
    @SerialName("id") val id: String,
    /** Which resolver found it, e.g. `bimi`. */
    @SerialName("source") val source: String,
    /** ISO-8601. */
    @SerialName("created_at") val createdAt: String,
)

/** How far a refresh got. */
@Serializable
data class RefreshResponse(
    /** False means these are the final numbers of that run rather than a snapshot of it. */
    @SerialName("running") val running: Boolean,
    /** Whether it visits the whole address book rather than only the addresses without a picture. */
    @SerialName("all") val all: Boolean,
    @SerialName("total") val total: Int,
    @SerialName("done") val done: Int,
    /** Addresses a picture was found for, out of the ones visited so far. */
    @SerialName("found") val found: Int,
)
