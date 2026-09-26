package es.jvbabi.overmail.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import kotlin.uuid.Uuid

@Entity(
    tableName = "email_labels",
    foreignKeys = [
        ForeignKey(
            entity = DbEmail::class,
            parentColumns = ["id"],
            childColumns = ["email_id"],
            onUpdate = ForeignKey.CASCADE,
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = DbLabels::class,
            parentColumns = ["id"],
            childColumns = ["label_id"],
            onUpdate = ForeignKey.CASCADE,
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["label_id"])
    ],
    primaryKeys = ["email_id", "label_id"],
)
data class DbEmailLabels(
    @ColumnInfo(name = "email_id") val emailId: Uuid,
    @ColumnInfo(name = "label_id") val labelId: Uuid,
)
