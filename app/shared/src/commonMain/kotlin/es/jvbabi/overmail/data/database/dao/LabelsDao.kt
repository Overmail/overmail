package es.jvbabi.overmail.data.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import es.jvbabi.overmail.data.database.entity.DbLabels
import es.jvbabi.overmail.data.database.entity.composed.EmbeddedLabel
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

@Dao
interface LabelsDao {
    @Query("SELECT * FROM labels WHERE overmail_account_id = :overmailAccountId")
    fun allForAccount(overmailAccountId: Uuid): Flow<List<EmbeddedLabel>>

    @Upsert
    suspend fun upsert(labels: List<DbLabels>)
}
