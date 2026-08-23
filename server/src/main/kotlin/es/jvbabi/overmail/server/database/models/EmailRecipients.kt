package es.jvbabi.overmail.server.database.models

import es.jvbabi.overmail.server.domain.models.EmailRecipientType
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.UuidTable

/** Links an [Emails] row to the [EmailUsers] it was addressed to, per header field. */
object EmailRecipients : UuidTable("email_recipients") {
    val email = reference("email_id", Emails, onDelete = ReferenceOption.CASCADE)
    val emailUser = reference("email_user_id", EmailUsers, onDelete = ReferenceOption.CASCADE)

    /** Display name from this mail's header field, absent for a bare `foo@bar.tld` address. */
    val name = varchar("name", 255).nullable()
    val type = enumerationByName<EmailRecipientType>("type", 16)

    init {
        uniqueIndex(email, emailUser, type)
    }
}
