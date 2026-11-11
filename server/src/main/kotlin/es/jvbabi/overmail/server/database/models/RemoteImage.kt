package es.jvbabi.overmail.server.database.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import kotlin.uuid.Uuid

class RemoteImage(id: EntityID<Uuid>) : UuidEntity(id) {
    companion object : UuidEntityClass<RemoteImage>(RemoteImages)

    var email by Email referencedOn RemoteImages.email
    var url by RemoteImages.url
    var urlHash by RemoteImages.urlHash
    var contentType by RemoteImages.contentType
    var data by RemoteImages.data
}

/**
 * The images a mail's html body loads from the web, downloaded when the mail was imported, see
 * `util/RemoteImages.kt`. One row per url the body names, also for one that could not be had --
 * [data] is null then -- so it is not asked for again.
 */
object RemoteImages : UuidTable("remote_images") {
    val email = reference("email", Emails, onDelete = ReferenceOption.CASCADE)

    /** The url as the body names it, html entities decoded. */
    val url = text("url")

    /**
     * The sha-256 of [url], in hex: what tells two urls apart in the index. A url may be longer
     * than an index entry in Postgres can be -- a signed one runs to kilobytes.
     */
    val urlHash = varchar("url_hash", 64)

    /** The type the server sent; null with [data]. */
    val contentType = varchar("content_type", 255).nullable()

    /** The image, or null when it could not be had: not an image, too large, or refused. */
    val data = blob("data").nullable()

    init {
        uniqueIndex(email, urlHash)
    }
}
