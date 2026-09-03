package es.jvbabi.overmail.server.http.webapp.content

import es.jvbabi.overmail.server.database.models.EmailArchiveAction
import es.jvbabi.overmail.server.database.models.EmailArchives
import es.jvbabi.overmail.server.database.models.EmailAvatars
import es.jvbabi.overmail.server.database.models.EmailLabels
import es.jvbabi.overmail.server.database.models.EmailUsers
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.database.models.Labels
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.http.avatar.avatarPadding
import es.jvbabi.overmail.server.http.avatar.avatarUrlOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

/**
 * Everything a screen shows of a mail without opening it: what a stack card, a table row and a
 * chip are all drawn from. The body is not in here -- that is `GET /api/emails/{id}/body`.
 *
 * One shape for the snapshot and for every update afterwards, so a client merges what arrives
 * over what it had instead of telling the two apart.
 */
@Serializable
data class EmailMeta(
    @SerialName("id") val id: Uuid,
    @SerialName("subject") val subject: String,
    /** Unix seconds. */
    @SerialName("sent") val sent: Long,
    @SerialName("is_read") val isRead: Boolean,
    /**
     * `unarchive`, `archive` or `spam` -- the latest event of the archive log. It travels with
     * the mail so a listing can drop a row that left the mailbox without asking what its window
     * holds any more.
     */
    @SerialName("archive_state") val archiveState: String,
    @SerialName("sender") val sender: Sender,
    @SerialName("labels") val labels: List<Label>,
) {
    @Serializable
    data class Sender(
        @SerialName("id") val id: Uuid,
        /** Display name from this mail's header, absent for a bare address. */
        @SerialName("name") val name: String?,
        @SerialName("address") val address: String,
        /** Null while no picture has been found for the address; the client shows initials then. */
        @SerialName("avatar_url") val avatarUrl: String?,
        /** How much of its box the picture gives up to fit a circle; null when none. */
        @SerialName("avatar_padding") val avatarPadding: Double?,
    )

    @Serializable
    data class Label(
        @SerialName("id") val id: Uuid,
        @SerialName("name") val name: String,
        @SerialName("color") val color: String,
        @SerialName("description") val description: String?,
        /** Why the agent attached it, when it was the agent. */
        @SerialName("assignment_reason") val assignmentReason: String?,
        @SerialName("created_by_agent") val createdByAgent: Boolean,
    )
}

/**
 * The metadata of [ids] that belong to [userId], in no particular order.
 *
 * An id that does not exist or is somebody else's is simply missing from the answer -- the caller
 * cannot tell the two apart, and neither can the client.
 *
 * Three queries rather than one join: the labels and the archive log are one-to-many, and joining
 * them onto the mail would multiply its row and hand every mail's subject over once per label.
 *
 * Has to run inside a transaction.
 */
fun loadEmailMeta(userId: User.Id, ids: Collection<Uuid>): List<EmailMeta> {
    if (ids.isEmpty()) return emptyList()

    val wanted = ids.distinct()

    val labelsByEmail = (EmailLabels innerJoin Labels)
        .select(
            EmailLabels.email,
            EmailLabels.reason,
            Labels.id,
            Labels.name,
            Labels.color,
            Labels.description,
            Labels.createdByAgent,
        )
        .where { EmailLabels.email inList wanted }
        .toList()
        .groupBy({ row -> row[EmailLabels.email].value }) { row ->
            EmailMeta.Label(
                id = row[Labels.id].value,
                name = row[Labels.name],
                color = row[Labels.color],
                description = row[Labels.description],
                assignmentReason = row[EmailLabels.reason],
                createdByAgent = row[Labels.createdByAgent],
            )
        }

    // Read in order and collected into a map, so the last event of a mail is the one that stays:
    // the archive table is a log, and only its latest row says where the mail is now.
    val archiveByEmail = EmailArchives
        .select(EmailArchives.email, EmailArchives.action, EmailArchives.createdAt)
        .where { EmailArchives.email inList wanted }
        .orderBy(EmailArchives.createdAt, SortOrder.ASC)
        .associate { row -> row[EmailArchives.email].value to row[EmailArchives.action] }

    // Columns, not the entity: loading an Email reads its raw source with it.
    return Emails
        .join(ImapAccounts, JoinType.INNER, Emails.imapAccount, ImapAccounts.id)
        .join(EmailUsers, JoinType.INNER, Emails.sender, EmailUsers.id)
        .leftJoin(EmailAvatars)
        .select(
            Emails.id,
            Emails.subject,
            Emails.senderName,
            Emails.sent,
            Emails.isRead,
            EmailUsers.id,
            EmailUsers.address,
            EmailUsers.avatar,
            EmailAvatars.circlePadding,
        )
        .where { (Emails.id inList wanted) and (ImapAccounts.user eq userId) }
        .map { row ->
            val id = row[Emails.id].value
            EmailMeta(
                id = id,
                subject = row[Emails.subject],
                sent = row[Emails.sent].epochSeconds,
                isRead = row[Emails.isRead],
                archiveState = (archiveByEmail[id] ?: EmailArchiveAction.Unarchive).wire(),
                sender = EmailMeta.Sender(
                    id = row[EmailUsers.id].value,
                    name = row[Emails.senderName],
                    address = row[EmailUsers.address],
                    avatarUrl = row.avatarUrlOrNull(),
                    avatarPadding = row.avatarPadding(),
                ),
                labels = (labelsByEmail[id] ?: emptyList()).distinctBy { it.id },
            )
        }
}

/** Spelled out here rather than by serializing the enum, which would put Kotlin names on the wire. */
private fun EmailArchiveAction.wire(): String = when (this) {
    EmailArchiveAction.Archive -> "archive"
    EmailArchiveAction.Unarchive -> "unarchive"
    EmailArchiveAction.Spam -> "spam"
}
