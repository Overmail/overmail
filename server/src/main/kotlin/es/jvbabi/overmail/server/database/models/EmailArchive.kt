package es.jvbabi.overmail.server.database.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.uuid.Uuid

class EmailArchive(id: EntityID<Uuid>) : UuidEntity(id) {
    companion object : UuidEntityClass<EmailArchive>(EmailArchives)

    var email by Email referencedOn EmailArchives.email
    var action by EmailArchives.action
    var createdAt by EmailArchives.createdAt
}

object EmailArchives : UuidTable("email_archives") {
    val email = reference("email_id", Emails, onDelete = ReferenceOption.CASCADE)
    val action = enumerationByName<EmailArchiveAction>("action", 12)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
}

enum class EmailArchiveAction {
    Archive, Unarchive, Spam
}
