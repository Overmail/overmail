package es.jvbabi.overmail.data.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import androidx.compose.ui.graphics.Color
import es.jvbabi.overmail.data.database.entity.DbEmail
import es.jvbabi.overmail.data.database.entity.DbEmailAttachment
import es.jvbabi.overmail.data.database.entity.DbEmailLabels
import es.jvbabi.overmail.data.database.entity.DbEmailRecipients
import es.jvbabi.overmail.data.database.entity.DbLabels
import es.jvbabi.overmail.data.database.entity.DbParticipant
import es.jvbabi.overmail.data.database.entity.composed.EmbeddedEmail
import es.jvbabi.overmail.domain.model.ArchivedState
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

@Dao
interface EmailsDao {
    @Transaction
    @Query("SELECT * FROM email WHERE overmail_account_id = :overmailAccountId")
    fun getAllEmailsForAccount(overmailAccountId: Uuid): Flow<List<EmbeddedEmail>>

    /** The mail of [id], and again on every change to it; null while it is not here. */
    @Transaction
    @Query("SELECT * FROM email WHERE id = :id")
    fun getById(id: Uuid): Flow<EmbeddedEmail?>

    @Query("SELECT id FROM email WHERE overmail_account_id = :overmailAccountId")
    suspend fun allIds(overmailAccountId: Uuid): List<Uuid>

    /** Which of [ids] are here already. */
    @Query("SELECT id FROM email WHERE id IN (:ids)")
    suspend fun existingIds(ids: List<Uuid>): List<Uuid>

    @Query("DELETE FROM email WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Uuid>)

    @Query("UPDATE email SET archive_state = :archivedState WHERE id = :id")
    suspend fun setArchivedState(id: Uuid, archivedState: ArchivedState)

    @Query("UPDATE email SET is_read = :isRead WHERE id = :id")
    suspend fun setRead(id: Uuid, isRead: Boolean)

    /**
     * Stores what the server said about [emails], replacing what was here of them.
     *
     * A mail's labels, recipients and attachments are the whole list every time, so they are replaced rather
     * than merged. Participants and labels are shared with the searches, which know more of them
     * than a mail does -- how much mail they hold, when a label was made -- so a row that exists
     * keeps that and only takes over what a mail says of it.
     */
    @Transaction
    suspend fun store(
        emails: List<DbEmail>,
        participants: List<DbParticipant>,
        labels: List<DbLabels>,
        emailLabels: List<DbEmailLabels>,
        recipients: List<DbEmailRecipients>,
        attachments: List<DbEmailAttachment>,
    ) {
        insertParticipantsIfMissing(participants)
        updateParticipantsFromEmails(participants.map { ParticipantFromEmail(it.id, it.email, it.avatarUrl, it.avatarPadding) })
        insertLabelsIfMissing(labels)
        updateLabelsFromEmails(labels.map { LabelFromEmail(it.id, it.name, it.description, it.color, it.createdByAgent) })

        upsertEmails(emails)
        val ids = emails.map { it.id }
        deleteLabelsOf(ids)
        insertEmailLabels(emailLabels)
        deleteRecipientsOf(ids)
        insertRecipients(recipients)
        deleteAttachmentsOf(ids)
        insertAttachments(attachments)
    }

    /** A correspondent's picture changed. Only touches the ones that are here. */
    @Update(entity = DbParticipant::class)
    suspend fun updateParticipantsFromEmails(participants: List<ParticipantFromEmail>)

    @Upsert
    suspend fun upsertEmails(emails: List<DbEmail>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertParticipantsIfMissing(participants: List<DbParticipant>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLabelsIfMissing(labels: List<DbLabels>)

    @Update(entity = DbLabels::class)
    suspend fun updateLabelsFromEmails(labels: List<LabelFromEmail>)

    @Query("DELETE FROM email_labels WHERE email_id IN (:emailIds)")
    suspend fun deleteLabelsOf(emailIds: List<Uuid>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEmailLabels(emailLabels: List<DbEmailLabels>)

    @Query("DELETE FROM email_recipients WHERE email_id IN (:emailIds)")
    suspend fun deleteRecipientsOf(emailIds: List<Uuid>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRecipients(recipients: List<DbEmailRecipients>)

    @Query("DELETE FROM email_attachments WHERE email_id IN (:emailIds)")
    suspend fun deleteAttachmentsOf(emailIds: List<Uuid>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAttachments(attachments: List<DbEmailAttachment>)
}

/** What a mail says of a participant; the name is left alone, a mail only has its own header's. */
data class ParticipantFromEmail(
    @ColumnInfo(name = "id") val id: Uuid,
    @ColumnInfo(name = "email") val email: String,
    @ColumnInfo(name = "avatar_url") val avatarUrl: String?,
    @ColumnInfo(name = "avatar_padding") val avatarPadding: Double?,
)

/** What a mail says of a label. */
data class LabelFromEmail(
    @ColumnInfo(name = "id") val id: Uuid,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "description") val description: String?,
    @ColumnInfo(name = "color") val color: Color,
    @ColumnInfo(name = "created_by_agent") val createdByAgent: Boolean,
)
