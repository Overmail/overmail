package es.jvbabi.overmail.server.database.models

import org.jetbrains.exposed.v1.core.dao.id.UuidTable

object Users : UuidTable("users") {
    val username = varchar("username", 255).uniqueIndex()
    val email = varchar("email", 255).uniqueIndex()
}
