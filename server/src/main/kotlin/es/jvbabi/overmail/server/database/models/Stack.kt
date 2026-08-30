package es.jvbabi.overmail.server.database.models

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.uuid.Uuid

class Stack(id: EntityID<Uuid>) : UuidEntity(id) {
    companion object : UuidEntityClass<Stack>(Stacks)

    var name by Stacks.name
    var createdAt by Stacks.createdAt
    var createdByAgent by Stacks.createdByAgent
}

object Stacks : UuidTable("stacks") {
    val name = varchar("name", 255)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val createdByAgent = bool("created_by_agent")
}