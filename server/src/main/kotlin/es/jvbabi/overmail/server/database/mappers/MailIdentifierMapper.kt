package es.jvbabi.overmail.server.database.mappers

import es.jvbabi.overmail.server.database.models.MailIdentifiers
import es.jvbabi.overmail.server.domain.models.MailIdentifier
import org.jetbrains.exposed.v1.core.ResultRow

fun ResultRow.toMailIdentifier(): MailIdentifier = MailIdentifier(
    id = this[MailIdentifiers.id].value,
    emailId = this[MailIdentifiers.email].value,
    identifier = this[MailIdentifiers.identifier],
    createdAt = this[MailIdentifiers.createdAt],
)
