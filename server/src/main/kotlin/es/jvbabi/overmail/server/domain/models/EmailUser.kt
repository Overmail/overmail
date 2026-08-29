package es.jvbabi.overmail.server.domain.models

import kotlin.uuid.Uuid

/**
 * An address of [user]'s address book. Carries no display name: those differ per mail, see
 * [Email.senderName] and [EmailRecipient.name].
 */
data class EmailUser(
    val id: Id,
    val user: User,
    val address: String,
) {
    typealias Id = Uuid
}
