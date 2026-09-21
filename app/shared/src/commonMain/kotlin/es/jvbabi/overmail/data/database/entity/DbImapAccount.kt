package es.jvbabi.overmail.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import kotlin.uuid.Uuid

/** A mailbox connected to the account on the server (`ImapAccounts`), without its password. */
@Entity(
    tableName = "imap_accounts",
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
data class DbImapAccount(
    @ColumnInfo(name = "id") val id: Uuid,
    @ColumnInfo(name = "host") val host: String,
    @ColumnInfo(name = "port") val port: Int,
    /** The imap login, which for most providers is the address itself. */
    @ColumnInfo(name = "username") val username: String,
    /** Whether the importer for it is switched off. */
    @ColumnInfo(name = "is_paused") val isPaused: Boolean,
    @ColumnInfo(name = "email_count") val emailCount: Long,
    @ColumnInfo(name = "overmail_account_id") val overmailAccountId: Uuid,
)
