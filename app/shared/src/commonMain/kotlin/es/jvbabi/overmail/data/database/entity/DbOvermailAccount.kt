package es.jvbabi.overmail.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import es.jvbabi.overmail.domain.model.OvermailAccount
import kotlin.uuid.Uuid

@Entity(
    tableName = "overmail_account",
    indices = [
        Index(value = ["id"], unique = true)
    ],
    primaryKeys = ["id"]
)
data class DbOvermailAccount(
    @ColumnInfo(name = "id") val id: Uuid,
    @ColumnInfo(name = "username") val username: String,
    @ColumnInfo(name = "first_name") val firstName: String,
    @ColumnInfo(name = "last_name") val lastName: String,
    @ColumnInfo(name = "email") val email: String,
    @ColumnInfo(name = "homeserver") val homeserver: String,
) {
    fun toModel(): OvermailAccount = OvermailAccount(
        id = id,
        username = username,
        firstName = firstName,
        lastName = lastName,
        email = email,
        homeserver = homeserver,
    )
}