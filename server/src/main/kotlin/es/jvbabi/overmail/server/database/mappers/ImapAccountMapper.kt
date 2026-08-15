package es.jvbabi.overmail.server.database.mappers

import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.domain.models.ImapAccount
import es.jvbabi.overmail.server.domain.models.User
import org.jetbrains.exposed.v1.core.ResultRow

/**
 * Maps a row of [ImapAccounts] to its domain model. [user] has to be resolved by the caller,
 * either from a joined [es.jvbabi.overmail.server.database.models.Users] row via
 * [toUser] or from an already known user.
 */
fun ResultRow.toImapAccount(user: User): ImapAccount = ImapAccount(
    id = this[ImapAccounts.id].value,
    user = user,
    host = this[ImapAccounts.host],
    port = this[ImapAccounts.port],
    username = this[ImapAccounts.username],
    password = this[ImapAccounts.password],
)
