package es.jvbabi.overmail.server.database.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.uuid.Uuid

class Share(id: EntityID<Uuid>): UuidEntity(id) {
    companion object : UuidEntityClass<Share>(Shares)

    var email by Email referencedOn Shares.email
    var sharedAt by Shares.sharedAt
    var shareName by Shares.shareName
    var includeLabels by Shares.includeLabels
    var validUntil by Shares.validUntil
    var passwordHash by Shares.passwordHash
    var allowMetadataWithoutPassword by Shares.allowMetadataWithoutPassword
}

object Shares : UuidTable("shares") {
    val email = reference("email_id", Emails, onDelete = ReferenceOption.CASCADE)
    val sharedAt = timestamp("shared_at").defaultExpression(CurrentTimestamp)
    val shareName = varchar("share_name", 255).nullable()
    val includeLabels = bool("include_labels")
    val validUntil = timestamp("valid_until").nullable()

    /**
     * What a visitor's password is checked against, or null when the link needs none.
     *
     * The encoded form of `SharePassword`, never the password itself -- a share link is handed to
     * people outside this installation, and the row it points at is the only thing standing
     * between them and the mail.
     */
    val passwordHash = varchar("password_hash", 255).nullable()

    /**
     * Whether subject, sender and date are shown before the password is entered.
     *
     * Only means anything with a [passwordHash] set; without one everything is open anyway.
     */
    val allowMetadataWithoutPassword = bool("allow_metadata_without_password").default(false)
}
