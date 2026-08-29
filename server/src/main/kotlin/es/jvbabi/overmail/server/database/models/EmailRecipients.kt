package es.jvbabi.overmail.server.database.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import kotlin.uuid.Uuid

enum class EmailRecipientType { RECIPIENT, CC, BCC }

/** Links an [Emails] row to the [EmailUsers] it was addressed to, per header field. */
object EmailRecipients : UuidTable("email_recipients") {
    val email = reference("email_id", Emails, onDelete = ReferenceOption.CASCADE)
    val emailUser = reference("email_user_id", EmailUsers)

    /** Display name from this mail's header field, absent for a bare `foo@bar.tld` address. */
    val name = varchar("name", 255).nullable()
    val type = enumerationByName<EmailRecipientType>("type", 16)

    init {
        uniqueIndex(email, emailUser, type)
    }
}

class EmailRecipient(id: EntityID<Uuid>) : UuidEntity(id) {
    companion object : UuidEntityClass<EmailRecipient>(EmailRecipients)

    var email by Email referencedOn EmailRecipients.email
    var emailUser by EmailUser referencedOn EmailRecipients.emailUser
    var name by EmailRecipients.name
    var type by EmailRecipients.type
}
