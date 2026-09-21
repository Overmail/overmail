package es.jvbabi.overmail.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import kotlin.uuid.Uuid

/** A correspondent of the account, an address book entry on the server (`EmailUsers`). */
@Entity(
    tableName = "participants",
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
data class DbParticipant(
    @ColumnInfo(name = "id") val id: Uuid,
    /** The name they write under most, null when they only ever sent a bare address. */
    @ColumnInfo(name = "name") val name: String?,
    @ColumnInfo(name = "email") val email: String,
    /** Path of their picture on the homeserver, `/api/avatars/<id>`. */
    @ColumnInfo(name = "avatar_url") val avatarUrl: String?,
    /** How much of its box that picture gives up to fit a circle, see the server's `EmailAvatars`. */
    @ColumnInfo(name = "avatar_padding") val avatarPadding: Double?,
    @ColumnInfo(name = "email_count") val emailCount: Long,
    @ColumnInfo(name = "overmail_account_id") val overmailAccountId: Uuid,
)
