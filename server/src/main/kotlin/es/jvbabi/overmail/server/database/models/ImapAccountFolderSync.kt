package es.jvbabi.overmail.server.database.models

import es.jvbabi.overmail.server.database.OvermailDatabase
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.json.jsonb
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ImapAccountFolderSync(id: EntityID<Uuid>): UuidEntity(id) {
    companion object : UuidEntityClass<ImapAccountFolderSync>(ImapAccountFolderSyncs)

    var imapAccount by ImapAccount referencedOn ImapAccountFolderSyncs.imapAccount
    var folder by ImapAccountFolderSyncs.folder
    var aiImport by ImapAccountFolderSyncs.aiImport
    var imapPush by ImapAccountFolderSyncs.imapPush
    var createdAt by ImapAccountFolderSyncs.createdAt

    @Serializable
    sealed class AiImportSettings {
        @Serializable
        @SerialName("only_new_messages")
        data object OnlyNewMessages : AiImportSettings()

        @Serializable
        @SerialName("all_messages")
        data object AllMessages : AiImportSettings()

        @Serializable
        @SerialName("after_date")
        data class AfterDate(@SerialName("date") val date: Instant) : AiImportSettings()
    }
}

object ImapAccountFolderSyncs : UuidTable("imap_account_folder_syncs") {
    val imapAccount = reference("imap_account_id", ImapAccounts, onDelete = ReferenceOption.CASCADE)
    val folder = varchar("folder_id", 128)
    val aiImport = jsonb<ImapAccountFolderSync.AiImportSettings>("ai_import", OvermailDatabase.json)
    val imapPush = bool("imap_push")
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
}
