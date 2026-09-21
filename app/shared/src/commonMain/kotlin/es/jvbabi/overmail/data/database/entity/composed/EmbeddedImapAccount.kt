package es.jvbabi.overmail.data.database.entity.composed

import androidx.room.Embedded
import androidx.room.Relation
import es.jvbabi.overmail.data.database.entity.DbImapAccount
import es.jvbabi.overmail.data.database.entity.DbOvermailAccount
import es.jvbabi.overmail.domain.model.ImapAccount

data class EmbeddedImapAccount(
    @Embedded val imapAccount: DbImapAccount,
    @Relation(
        entity = DbOvermailAccount::class,
        parentColumn = "overmail_account_id",
        entityColumn = "id",
    ) val account: DbOvermailAccount
) {
    fun toModel(): ImapAccount = ImapAccount(
        id = imapAccount.id,
        host = imapAccount.host,
        port = imapAccount.port,
        username = imapAccount.username,
        isPaused = imapAccount.isPaused,
        emailCount = imapAccount.emailCount,
        overmailAccount = account.toModel(),
    )
}
