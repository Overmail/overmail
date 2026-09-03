package es.jvbabi.overmail.server.database.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.uuid.Uuid

class AiChat(id: EntityID<Id>) : UuidEntity(id) {
    companion object : UuidEntityClass<AiChat>(AiChats)
    typealias Id = Uuid

    var user by User referencedOn AiChats.userId
    var name by AiChats.name
    var nameSetByUser by AiChats.nameSetByUser
    var createdAt by AiChats.createdAt
    val messages by AiChatMessage referrersOn AiChatMessages.chatId
}

object AiChats : UuidTable("ai_chats") {
    val userId = reference("user_id", Users, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 255).nullable()
    val nameSetByUser = bool("name_set_by_user")
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
}