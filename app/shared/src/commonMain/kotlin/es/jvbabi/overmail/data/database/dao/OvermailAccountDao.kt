package es.jvbabi.overmail.data.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import es.jvbabi.overmail.data.database.entity.DbOvermailAccount
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

@Dao
interface OvermailAccountDao {
    @Query("SELECT * FROM overmail_account")
    fun all(): Flow<List<DbOvermailAccount>>

    @Upsert
    suspend fun insert(account: DbOvermailAccount)

    @Query("SELECT * FROM overmail_account WHERE id = :id")
    fun findById(id: Uuid): Flow<DbOvermailAccount?>
}