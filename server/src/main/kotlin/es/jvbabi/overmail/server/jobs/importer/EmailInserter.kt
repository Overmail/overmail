package es.jvbabi.overmail.server.jobs.importer

import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.kamel.Email as KamelEmail

interface EmailInserter {
    /**
     * Stores [mail] under [account] with its addresses, recipients, attachments and read flag,
     * unless it is already there.
     *
     * [mail] is one of a mailbox or one parsed from its source (`KamelEmail.parse`). Its body is
     * loaded in here and only for a mail that is new -- for a mail of a mailbox that is the one
     * expensive download.
     */
    suspend fun importEmailIntoDatabase(mail: KamelEmail, account: ImapConnection): Result

    sealed interface Result {
        data object AlreadyExists : Result

        /** The mail lacks what a stored mail needs. Trying again changes nothing. */
        data class Rejected(val reason: String) : Result

        /** [email] is out of its transaction: its own columns are readable, its references are not. */
        data class Imported(val email: Email) : Result
    }
}
