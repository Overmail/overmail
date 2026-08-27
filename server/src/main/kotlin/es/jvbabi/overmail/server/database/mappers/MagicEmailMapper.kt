package es.jvbabi.overmail.server.database.mappers

import es.jvbabi.overmail.server.database.models.MagicEmails
import es.jvbabi.overmail.server.domain.models.MagicEmail
import org.jetbrains.exposed.v1.core.ResultRow

/** The provider icon is left off, as [MagicEmail] says: nothing fetches one yet. */
fun ResultRow.toMagicEmail(): MagicEmail = MagicEmail(
    id = this[MagicEmails.id].value,
    emailId = this[MagicEmails.email].value,
    provider = this[MagicEmails.provider],
    kind = this[MagicEmails.kind],
    payload = this[MagicEmails.payload],
    validUntil = this[MagicEmails.validUntil],
    usedAt = this[MagicEmails.usedAt],
    createdAt = this[MagicEmails.createdAt],
)
