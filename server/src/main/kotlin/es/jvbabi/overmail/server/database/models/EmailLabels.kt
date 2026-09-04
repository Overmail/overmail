package es.jvbabi.overmail.server.database.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
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

/**
 * Hangs [labelId] on [emailId] unless it is already there, and says whether it wrote anything --
 * which is what decides whether there is a change to announce. Inside a transaction, and columns
 * only: neither side of the pair has to be loaded for this.
 *
 * Read and then insert rather than an insert that ignores the conflict: `INSERT IGNORE` is not
 * something every dialect this runs on has, and the unique index above is the backstop for two of
 * these at the same time.
 *
 * Neither caller of this is the agent, so there is no reason to record either -- see
 * [EmailLabels.reason].
 */
fun attachLabelToEmail(emailId: Uuid, labelId: Uuid): Boolean {
    val already = EmailLabels
        .select(EmailLabels.id)
        .where { (EmailLabels.email eq emailId) and (EmailLabels.label eq labelId) }
        .empty()
        .not()
    if (already) return false

    EmailLabels.insert {
        it[email] = emailId
        it[label] = labelId
        it[labeledByAgent] = false
        it[reason] = null
    }

    return true
}
/**
 * Takes [labelId] off [emailId] and says whether there was anything to take off -- which is what
 * decides whether there is a change to announce. Inside a transaction.
 */
fun detachLabelFromEmail(emailId: Uuid, labelId: Uuid): Boolean =
    EmailLabels.deleteWhere { (email eq emailId) and (label eq labelId) } > 0
