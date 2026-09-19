package es.jvbabi.overmail.data.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import es.jvbabi.overmail.data.database.entity.DbOvermailAccount
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

@Dao
interface OvermailAccountDao {

    @Upsert
    suspend fun upsert(account: DbOvermailAccount)

    @Query("DELETE FROM overmail_account WHERE id = :id")
    suspend fun delete(id: Uuid)

    @Query("SELECT * FROM overmail_account WHERE id = :id")
    fun getById(id: Uuid): Flow<DbOvermailAccount?>

    @Query("SELECT * FROM overmail_account")
    fun getAll(): Flow<List<DbOvermailAccount>>
}
