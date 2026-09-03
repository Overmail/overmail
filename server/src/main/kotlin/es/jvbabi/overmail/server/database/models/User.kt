package es.jvbabi.overmail.server.database.models

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import kotlin.uuid.Uuid

object Users : UuidTable("users") {
    val username = varchar("username", 255).uniqueIndex()
    val email = varchar("email", 255).uniqueIndex()
}

class User(id: EntityID<Id>) : UuidEntity(id) {
    companion object : UuidEntityClass<User>(Users)
    typealias Id = Uuid

    var username by Users.username
    var email by Users.email
}
