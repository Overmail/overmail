package es.jvbabi.overmail.data.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import es.jvbabi.overmail.data.database.entity.DbImapAccount
import es.jvbabi.overmail.data.database.entity.composed.EmbeddedImapAccount
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

@Dao
interface ImapAccountsDao {
    /** By login, the order `GET /api/users/me/inboxes` answers in. */
    @Transaction
    @Query("SELECT * FROM imap_accounts WHERE overmail_account_id = :overmailAccountId ORDER BY username")
    fun allForAccount(overmailAccountId: Uuid): Flow<List<EmbeddedImapAccount>>

    @Upsert
    suspend fun upsert(imapAccounts: List<DbImapAccount>)

    /** Which of [ids] are here already. */
    @Query("SELECT id FROM imap_accounts WHERE id IN (:ids)")
    suspend fun existingIds(ids: List<Uuid>): List<Uuid>

    @Query("DELETE FROM imap_accounts WHERE overmail_account_id = :overmailAccountId AND id NOT IN (:keep)")
    suspend fun deleteOthers(overmailAccountId: Uuid, keep: List<Uuid>)

    /**
     * Makes the cache the server's list: the server always answers with every mailbox, so one
     * missing from it was removed and goes here too.
     */
    @Transaction
    suspend fun replaceAll(overmailAccountId: Uuid, imapAccounts: List<DbImapAccount>) {
        deleteOthers(overmailAccountId, imapAccounts.map { it.id })
        upsert(imapAccounts)
    }
}
