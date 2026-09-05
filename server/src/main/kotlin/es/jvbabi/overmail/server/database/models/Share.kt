package es.jvbabi.overmail.server.database.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.uuid.Uuid

class Share(id: EntityID<Uuid>): UuidEntity(id) {
    companion object : UuidEntityClass<Share>(Shares)

    var email by Email referencedOn Shares.email
    var sharedAt by Shares.sharedAt
    var shareName by Shares.shareName
    var includeLabels by Shares.includeLabels
    var validUntil by Shares.validUntil
}

object Shares : UuidTable("shares") {
    val email = reference("email_id", Emails, onDelete = ReferenceOption.CASCADE)
    val sharedAt = timestamp("shared_at").defaultExpression(CurrentTimestamp)
    val shareName = varchar("share_name", 255).nullable()
    val includeLabels = bool("include_labels")
    val validUntil = timestamp("valid_until").nullable()
}