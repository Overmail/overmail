package es.jvbabi.overmail.server.database.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import kotlin.uuid.Uuid

/**
 * A person appearing in a mail header (sender or recipient). Scoped per [Users] row: the same
 * address imported by two users yields two rows, so no user can see the other's address book.
 *
 * Identity only, no display name: senders reuse one address for many names (every GitHub
 * notification arrives as `notifications@github.com` with the acting username as its name), so the
 * name belongs to the single mail it came from -- see [Emails.senderName] and [EmailRecipients.name].
 */
object EmailUsers : UuidTable("email_users") {
    val user = reference("user_id", Users, onDelete = ReferenceOption.CASCADE)

    val address = varchar("address", 320)

    /**
     * The picture we found for [address], null while none was resolved (yet). `SET NULL` on
     * delete: dropping the cache is what puts an address back in front of the resolvers, so the
     * link has to come off with the row rather than block its deletion.
     */
    val avatar = optReference("avatar_id", EmailAvatars, onDelete = ReferenceOption.SET_NULL)

    init {
        uniqueIndex(user, address)
    }
}

class EmailUser(id: EntityID<Id>) : UuidEntity(id) {
    companion object : UuidEntityClass<EmailUser>(EmailUsers)
    typealias Id = Uuid

    var user by User referencedOn EmailUsers.user
    var address by EmailUsers.address

    /**
     * Reading this loads the picture bytes with it, see [EmailAvatar]. Everything that only wants
     * the url goes through [avatarId] instead.
     */
    var avatar by EmailAvatar optionalReferencedOn EmailUsers.avatar

    /** The id of the linked picture without loading its bytes, null while none was resolved. */
    val avatarId: EmailAvatar.Id? get() = readValues[EmailUsers.avatar]?.value
}
