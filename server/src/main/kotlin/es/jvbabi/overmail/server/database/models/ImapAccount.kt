package es.jvbabi.overmail.server.database.models

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import kotlin.uuid.Uuid

object ImapAccounts : UuidTable("imap_accounts") {
    val user = reference("user_id", Users)
    val host = varchar("host", 255)
    val port = integer("port")
    val username = varchar("username", 255)
    val password = varchar("password", 255)
}

class ImapAccount(id: EntityID<Uuid>) : UuidEntity(id) {
    companion object : UuidEntityClass<ImapAccount>(ImapAccounts)

    var user by User referencedOn ImapAccounts.user
    var host by ImapAccounts.host
    var port by ImapAccounts.port
    var username by ImapAccounts.username
    var password by ImapAccounts.password

    val folderSyncs by ImapAccountFolderSync referrersOn ImapAccountFolderSyncs.imapAccount
}
