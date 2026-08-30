package es.jvbabi.overmail.server.database.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.uuid.Uuid

class EmailLabel(id: EntityID<Uuid>) : UuidEntity(id) {
    companion object : UuidEntityClass<EmailLabel>(EmailLabels)

    var email by Email referencedOn EmailLabels.email
    var label by Label referencedOn EmailLabels.label
    var labeledAt by EmailLabels.labeledAt
    var labeledByAgent by EmailLabels.labeledByAgent
    var reason by EmailLabels.reason
}

object EmailLabels : UuidTable("email_labels") {
    val email = reference("email_id", Emails, onDelete = ReferenceOption.CASCADE)
    val label = reference("label_id", Labels, onDelete = ReferenceOption.CASCADE)
    val labeledAt = timestamp("labeled_at").defaultExpression(CurrentTimestamp)
    val labeledByAgent = bool("labeled_by_agent")
    val reason = varchar("reason", 512).nullable()

    init {
        // One row per (email, label): the database enforces what the check-then-insert in the
        // classification assumes, so concurrent runs cannot attach the same label twice.
        uniqueIndex(email, label)
    }
}