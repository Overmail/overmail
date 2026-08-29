package es.jvbabi.overmail.server.data

import es.jvbabi.overmail.server.domain.models.Email
import es.jvbabi.overmail.server.domain.models.EmailRecipient
import es.jvbabi.overmail.server.domain.models.EmailUser
import es.jvbabi.overmail.server.domain.models.ImapAccount
import es.jvbabi.overmail.server.domain.models.User

/**
 * The notifier per table and, in the constructor arguments, the foreign keys they are chained
 * along -- the one place that has to know the shape of the schema. A repository subscribes to the
 * notifier of the table it selects from and gets the parents through the chain.
 *
 * Injected as a whole and held for the lifetime of the application: these notifiers are the only
 * link between a write and the flows that have to reload because of it.
 */
class ChangeNotifiers {
    val users = EntityNotifier<User.Id>()
    val imapAccounts = EntityNotifier<ImapAccount.Id>(users)
    val emailUsers = EntityNotifier<EmailUser.Id>(users)
    val emails = EntityNotifier<Email.Id>(imapAccounts, emailUsers)
    val emailRecipients = EntityNotifier<EmailRecipient.Id>(emails, emailUsers)
}
