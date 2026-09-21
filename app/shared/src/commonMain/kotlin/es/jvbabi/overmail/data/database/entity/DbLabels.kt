package es.jvbabi.overmail.data.database.entity

import androidx.compose.ui.graphics.Color
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import es.jvbabi.overmail.domain.model.OvermailAccount
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Entity(
    tableName = "labels",
    foreignKeys = [
        ForeignKey(
            entity = DbOvermailAccount::class,
            parentColumns = ["id"],
            childColumns = ["overmail_account_id"],
            onUpdate = ForeignKey.CASCADE,
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index(value = ["overmail_account_id"])
    ],
    primaryKeys = ["id"],
)
data class DbLabels(
    @ColumnInfo(name = "id") val id: Uuid,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "description") val description: String?,
    @ColumnInfo(name = "color") val color: Color,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "created_by_agent") val createdByAgent: Boolean,
    @ColumnInfo(name = "email_count") val emailCount: Long,
    @ColumnInfo(name = "overmail_account_id") val overmailAccountId: Uuid,
)