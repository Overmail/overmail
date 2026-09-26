package es.jvbabi.overmail.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import es.jvbabi.overmail.domain.model.EmailRecipientType
import kotlin.uuid.Uuid

/**
 * Who a mail was addressed to, per header field; the server's `EmailRecipients`. The same
 * participant can be in more than one field of a mail, so the type is part of the key.
 */
@Entity(
    tableName = "email_recipients",
    foreignKeys = [
        ForeignKey(
            entity = DbEmail::class,
            parentColumns = ["id"],
            childColumns = ["email_id"],
            onUpdate = ForeignKey.CASCADE,
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = DbParticipant::class,
            parentColumns = ["id"],
            childColumns = ["participant_id"],
            onUpdate = ForeignKey.CASCADE,
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["participant_id"])
    ],
    primaryKeys = ["email_id", "participant_id", "type"],
)
data class DbEmailRecipients(
    @ColumnInfo(name = "email_id") val emailId: Uuid,
    @ColumnInfo(name = "participant_id") val participantId: Uuid,
    @ColumnInfo(name = "type") val type: EmailRecipientType,
)
