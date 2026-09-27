package es.jvbabi.overmail.data.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import es.jvbabi.overmail.data.database.entity.DbParticipant
import es.jvbabi.overmail.data.database.entity.composed.EmbeddedParticipant
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

@Dao
interface ParticipantsDao {
    /** Most written to the account first, the order `GET /api/senders/search` answers in. */
    @Transaction
    @Query("SELECT * FROM participants WHERE overmail_account_id = :overmailAccountId ORDER BY email_count DESC, name, email")
    fun allForAccount(overmailAccountId: Uuid): Flow<List<EmbeddedParticipant>>

    @Transaction
    @Query("SELECT * FROM participants WHERE overmail_account_id = :overmailAccountId AND id IN (:ids)")
    fun byIds(overmailAccountId: Uuid, ids: List<Uuid>): Flow<List<EmbeddedParticipant>>

    @Upsert
    suspend fun upsert(participants: List<DbParticipant>)

    @Transaction
    @Query("SELECT * FROM participants WHERE email IN (:emails)")
    fun getAllParticipantsWithEmails(emails: List<String>): Flow<List<EmbeddedParticipant>>
}
