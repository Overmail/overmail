package es.jvbabi.overmail.data.database.dao

import androidx.room.Dao
import androidx.room.Query
import es.jvbabi.overmail.data.database.entity.DbOvermailAccount
import kotlinx.coroutines.flow.Flow

@Dao
interface OvermailAccountDao {
    @Query("SELECT * FROM overmail_account")
    fun all(): Flow<List<DbOvermailAccount>>
}