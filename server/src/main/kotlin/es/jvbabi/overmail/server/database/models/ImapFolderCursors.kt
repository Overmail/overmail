package es.jvbabi.overmail.server.database.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * How far the importer has read a folder: every mail up to [lastSeenUid] is dealt with, so a pass
 * only asks the server for what came after it.
 *
 * Its own table rather than columns on [ImapAccountFolderSyncs]: those rows are replaced whenever
 * the settings of an inbox are saved, and the position must outlive that.
 *
 * No entity: the key is account and folder, and every access is a lookup or an upsert.
 */
object ImapFolderCursors : Table("imap_folder_cursors") {
    val imapAccount = reference("imap_account_id", ImapAccounts, onDelete = ReferenceOption.CASCADE)
    val folder = varchar("folder_id", 128)

    /** The `UIDVALIDITY` [lastSeenUid] was read under. Under another one it says nothing. */
    val uidValidity = long("uid_validity")
    val lastSeenUid = long("last_seen_uid")

    override val primaryKey = PrimaryKey(imapAccount, folder)
}
