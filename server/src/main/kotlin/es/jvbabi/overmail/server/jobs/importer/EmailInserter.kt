package es.jvbabi.overmail.server.jobs.importer

import es.jvbabi.overmail.server.database.models.ImapAccount
import es.jvbabi.overmail.kamel.Email as KamelEmail

interface EmailInserter {
    /**
     * [mail] is either one of a mailbox or one parsed from its source (`KamelEmail.parse`). Its
     * body is loaded in here, which for a mail of a mailbox is a download over its connection.
     *
     * [flags] are what the mailbox says about the mail. They are a parameter rather than read off
     * [mail], because a parsed mail has none and fails when asked.
     */
    suspend fun importEmailIntoDatabase(
        mail: KamelEmail,
        imapAccount: ImapAccount,
        flags: Set<KamelEmail.Flag> = emptySet(),
    ): EmailInserterImpl.Result
}
