package es.jvbabi.overmail.data.database.dao

import androidx.room.Dao
import androidx.room.Query
import es.jvbabi.overmail.data.database.entity.composed.EmbeddedEmail
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

@Dao
interface EmailsDao {
    @Query("SELECT * FROM email WHERE overmail_account_id = :overmailAccountId")
    fun getAllEmailsForAccount(overmailAccountId: Uuid): Flow<List<EmbeddedEmail>>
}