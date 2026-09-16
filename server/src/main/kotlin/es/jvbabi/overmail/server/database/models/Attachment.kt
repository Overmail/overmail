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
}

object Attachments : UuidTable("attachments") {
    val filename = varchar("filename", 255)
    val contentType = varchar("content_type", 255)
    val email = reference("email", Emails, onDelete = ReferenceOption.CASCADE)
    val size = long("size")
    val data = blob("data")
}