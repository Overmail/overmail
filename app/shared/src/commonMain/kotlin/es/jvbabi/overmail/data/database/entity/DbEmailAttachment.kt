package es.jvbabi.overmail.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import es.jvbabi.overmail.domain.model.Attachment
import kotlin.uuid.Uuid

/**
 * A file attached to a mail, as the server's `EmailMeta.Attachment` describes it. Only what a
 * list of them shows; the file itself is downloaded when somebody opens it.
 */
@Entity(
    tableName = "email_attachments",
    primaryKeys = ["id"],
    foreignKeys = [
        ForeignKey(
            entity = DbEmail::class,
            parentColumns = ["id"],
            childColumns = ["email_id"],
            onUpdate = ForeignKey.CASCADE,
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index(value = ["email_id"])
    ]
)
data class DbEmailAttachment(
    @ColumnInfo(name = "id") val id: Uuid,
    @ColumnInfo(name = "email_id") val emailId: Uuid,
    @ColumnInfo(name = "filename") val filename: String,
    @ColumnInfo(name = "content_type") val contentType: String,
    @ColumnInfo(name = "size") val size: Long,
) {
    fun toModel() = Attachment(
        id = id,
        filename = filename,
        contentType = contentType,
        size = size,
    )
}
