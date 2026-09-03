package es.jvbabi.overmail.server.http.avatar

import es.jvbabi.overmail.server.database.models.EmailAvatar
import es.jvbabi.overmail.server.database.models.EmailAvatars
import es.jvbabi.overmail.server.database.models.EmailUsers
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.select

/**
 * Where the bytes of a picture sit. The id is part of it, which is what makes the answer cacheable
 * forever -- see `getAvatar`.
 */
fun avatarUrl(avatarId: EmailAvatar.Id): String = "/api/avatars/$avatarId"

/** The picture on this row's address book entry, or null when it has none. */
fun ResultRow.avatarUrlOrNull(): String? = this[EmailUsers.avatar]?.value?.let(::avatarUrl)

/**
 * How much of its box that picture has to give up on every side to fit into a circle, see
 * [EmailAvatars.circlePadding]. Reading it needs the row to have joined `email_avatars`:
 *
 * ```
 * EmailUsers.leftJoin(EmailAvatars).select(..., EmailUsers.avatar, EmailAvatars.circlePadding)
 * ```
 *
 * The join is what keeps this off [EmailAvatar], which would read the whole picture with it.
 *
 * Null for a row with no picture, for one nothing has analysed yet, and for one that needs no
 * padding at all -- three ways of saying the same thing to a client: show it as it is.
 */
fun ResultRow.avatarPadding(): Double? = this[EmailAvatars.circlePadding]?.takeIf { it > 0 }

/**
 * The padding of these pictures, by id, leaving out the ones that need none.
 *
 * For callers that reach the address book through the entities rather than through columns, where
 * the join above is not available: [EmailAvatar] would read the whole picture to get at the number,
 * while a batch of mails has a handful of distinct pictures that one query answers together.
 *
 * Has to run inside a transaction.
 */
fun avatarPaddings(avatarIds: Collection<EmailAvatar.Id>): Map<EmailAvatar.Id, Double> {
    if (avatarIds.isEmpty()) return emptyMap()

    return EmailAvatars
        .select(EmailAvatars.id, EmailAvatars.circlePadding)
        .where { (EmailAvatars.id inList avatarIds.distinct()) and (EmailAvatars.circlePadding greater 0.0) }
        .associate { row -> row[EmailAvatars.id].value to row[EmailAvatars.circlePadding]!! }
}
