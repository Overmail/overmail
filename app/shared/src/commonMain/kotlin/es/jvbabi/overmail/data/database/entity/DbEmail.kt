package es.jvbabi.overmail.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Entity(
    tableName = "email",
)
data class DbEmail(
    @ColumnInfo(name = "id") val id: Uuid,
    @ColumnInfo(name = "overmail_account_id") val overmailAccountId: Uuid,
    @ColumnInfo(name = "imap_account_id") val imapAccountId: Uuid,
    @ColumnInfo(name = "sent_at") val sentAt: Instant,
    @ColumnInfo(name = "sent_from_participant_id") val sentFromParticipantId: Uuid,
    @ColumnInfo(name = "subject") val subject: String?,
    @ColumnInfo(name = "is_read") val isRead: Boolean,
)