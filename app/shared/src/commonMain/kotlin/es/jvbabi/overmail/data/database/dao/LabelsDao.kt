package es.jvbabi.overmail.data.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import es.jvbabi.overmail.data.database.entity.DbLabels
import es.jvbabi.overmail.data.database.entity.composed.EmbeddedLabel
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

@Dao
interface LabelsDao {
    /** Most used first, the order `GET /api/labels/search` answers in. */
    @Transaction
    @Query("SELECT * FROM labels WHERE overmail_account_id = :overmailAccountId ORDER BY email_count DESC, name")
    fun allForAccount(overmailAccountId: Uuid): Flow<List<EmbeddedLabel>>

    @Transaction
    @Query("SELECT * FROM labels WHERE overmail_account_id = :overmailAccountId AND id IN (:ids)")
    fun byIds(overmailAccountId: Uuid, ids: List<Uuid>): Flow<List<EmbeddedLabel>>

    @Upsert
    suspend fun upsert(labels: List<DbLabels>)
}
