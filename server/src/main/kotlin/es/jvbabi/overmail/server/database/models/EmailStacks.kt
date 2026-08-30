package es.jvbabi.overmail.server.database.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.uuid.Uuid

class EmailStack(id: EntityID<Uuid>) : UuidEntity(id) {
    companion object : UuidEntityClass<EmailStack>(EmailStacks)

    var email by Email referencedOn EmailStacks.email
    var stack by Stack referencedOn EmailStacks.stack
    var createdByAgent by EmailStacks.createdByAgent
    var reason by EmailStacks.reason
    var createdAt by EmailStacks.createdAt
}

object EmailStacks: UuidTable("email_stacks") {
    val email = reference("email_id", Emails, onDelete = ReferenceOption.CASCADE)
    val stack = reference("stack_id", Stacks, onDelete = ReferenceOption.CASCADE)
    val createdByAgent = bool("created_by_agent")
    val reason = varchar("reason", 512).nullable()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
}