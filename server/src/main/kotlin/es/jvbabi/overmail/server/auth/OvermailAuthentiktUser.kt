package es.jvbabi.overmail.server.auth

import es.jvbabi.authentikt.core.AuthentiktUser
import es.jvbabi.overmail.server.database.models.User

/**
 * How authentikt sees an account. The sign-in flow keeps this around across steps and requests,
 * long after the transaction that loaded the row is over -- which is why only the entity's own
 * columns are read here. They came with the row and need no database.
 */
class OvermailAuthentiktUser(user: User) : AuthentiktUser<User>(user) {
    override suspend fun getEmail(): String = user.email
    override suspend fun getUsername(): String = user.username
    override suspend fun getDisplayName(): String = user.username
}
