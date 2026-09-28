package es.jvbabi.overmail.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import es.jvbabi.overmail.domain.model.ArchivedState
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Entity(
    tableName = "email",
    primaryKeys = ["id"],
    foreignKeys = [
        ForeignKey(
            entity = DbOvermailAccount::class,
            parentColumns = ["id"],
            childColumns = ["overmail_account_id"],
            onUpdate = ForeignKey.CASCADE,
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = DbImapAccount::class,
            parentColumns = ["id"],
            childColumns = ["imap_account_id"],
            onUpdate = ForeignKey.CASCADE,
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = DbParticipant::class,
            parentColumns = ["id"],
            childColumns = ["sent_from_participant_id"],
            onUpdate = ForeignKey.CASCADE,
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["overmail_account_id"]),
        Index(value = ["imap_account_id"]),
        Index(value = ["sent_from_participant_id"]),
    ],
)
data class DbEmail(
    @ColumnInfo(name = "id") val id: Uuid,
    @ColumnInfo(name = "overmail_account_id") val overmailAccountId: Uuid,
    @ColumnInfo(name = "imap_account_id") val imapAccountId: Uuid,
    @ColumnInfo(name = "sent_at") val sentAt: Instant,
    @ColumnInfo(name = "sent_from_participant_id") val sentFromParticipantId: Uuid,
    @ColumnInfo(name = "subject") val subject: String?,
    @ColumnInfo(name = "preview") val preview: String?,
    @ColumnInfo(name = "has_text") val hasText: Boolean,
    @ColumnInfo(name = "has_html") val hasHtml: Boolean,
    @ColumnInfo(name = "is_read") val isRead: Boolean,
    @ColumnInfo(name = "archive_state") val archivedState: ArchivedState,
)