package es.jvbabi.overmail.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import kotlin.uuid.Uuid

@Entity(
    tableName = "overmail_account",
    primaryKeys = ["id"],
    indices = [Index(value = ["id"], unique = true)]
)
data class DbOvermailAccount(
    @ColumnInfo(name = "id") val id: Uuid,
    @ColumnInfo(name = "server_url") val serverUrl: String,
    @ColumnInfo(name = "username") val username: String,
    @ColumnInfo(name = "access_token") val accessToken: String,
)