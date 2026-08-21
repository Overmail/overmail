package es.jvbabi.overmail.server.database.mappers

import es.jvbabi.overmail.server.database.models.EmailRecipients
import es.jvbabi.overmail.server.domain.models.EmailRecipient
import es.jvbabi.overmail.server.domain.models.EmailUser
import org.jetbrains.exposed.v1.core.ResultRow

/**
 * Maps a row of [EmailRecipients] to its domain model. [emailUser] has to be resolved by the
 * caller, either from a joined [es.jvbabi.overmail.server.database.models.EmailUsers] row via
 * [toEmailUser] or from an already known email user.
 */
fun ResultRow.toEmailRecipient(emailUser: EmailUser): EmailRecipient = EmailRecipient(
    id = this[EmailRecipients.id].value,
    emailUser = emailUser,
    name = this[EmailRecipients.name],
    type = this[EmailRecipients.type],
)
