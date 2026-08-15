package es.jvbabi.overmail.server.auth

import es.jvbabi.authentikt.core.AuthentiktUser
import es.jvbabi.overmail.server.domain.models.User

/**
 * How authentikt sees an account. Wraps the domain model, not an Exposed entity: the sign-in flow
 * keeps the user around across steps and requests, which a row would not survive.
 */
class OvermailAuthentiktUser(user: User) : AuthentiktUser<User>(user) {
    override suspend fun getEmail(): String = user.email
    override suspend fun getUsername(): String = user.username
    override suspend fun getDisplayName(): String = user.username
}
