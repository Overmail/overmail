package es.jvbabi.overmail.data.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import es.jvbabi.overmail.data.database.entity.DbKeyValue
import kotlinx.coroutines.flow.Flow

@Dao
interface KeyValueDao {

    @Upsert
    suspend fun upsert(keyValue: DbKeyValue)

    @Query("DELETE FROM key_value WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("SELECT value FROM key_value WHERE `key` = :key")
    fun getValue(key: String): Flow<String?>
}