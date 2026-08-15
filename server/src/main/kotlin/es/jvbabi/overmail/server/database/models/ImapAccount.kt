package es.jvbabi.overmail.server.database.models

import org.jetbrains.exposed.v1.core.dao.id.UuidTable

object ImapAccounts : UuidTable("imap_accounts") {
    val user = reference("user_id", Users)
    val host = varchar("host", 255)
    val port = integer("port")
    val username = varchar("username", 255)
    val password = varchar("password", 255)
}
