package es.jvbabi.overmail.server.database.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import kotlin.uuid.Uuid

class Attachment(id: EntityID<Uuid>): UuidEntity(id) {
    companion object : UuidEntityClass<Attachment>(Attachments)

    var filename by Attachments.filename
    var contentType by Attachments.contentType
    var email by Email referencedOn Attachments.email
    var size by Attachments.size
    var data by Attachments.data
    var contentId by Attachments.contentId
    var isEmbedded by Attachments.isEmbedded
}

object Attachments : UuidTable("attachments") {
    val filename = varchar("filename", 255)
    val contentType = varchar("content_type", 255)
    val email = reference("email", Emails, onDelete = ReferenceOption.CASCADE)
    val size = long("size")
    val data = blob("data")

    /**
     * The `Content-ID` the html body shows it under, for an image the body references with a `cid:`
     * url; null for everything else. Such an image is part of the body rather than an attachment
     * to list, see `util/EmbeddedImages.kt`.
     */
    val contentId = varchar("content_id", 255).nullable()

    /**
     * Whether [contentId] is set, or null for an attachment stored before anything looked:
     * what `EmbeddedImageBackfill` works through.
     */
    val isEmbedded = bool("is_embedded").nullable()
}