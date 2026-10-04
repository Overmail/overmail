package es.jvbabi.overmail.server.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shorter is refused when a password is set. */
const val PASSWORD_MIN_LENGTH = 8

/** BCrypt reads no further than this, so a longer password would only look longer. */
const val PASSWORD_MAX_BYTES = 72

/** About a quarter of a second per hash, which is the point of it. */
private const val BCRYPT_COST = 12

/**
 * The hash stored in `users.password`. On [Dispatchers.Default]: hashing is deliberately slow and
 * all CPU, nothing for the IO pool or the event loop.
 */
suspend fun hashPassword(password: String): String = withContext(Dispatchers.Default) {
    BCrypt.withDefaults().hashToString(BCRYPT_COST, password.toCharArray())
}

/** Whether [password] is the one [hash] was made from. */
suspend fun verifyPassword(password: String, hash: String): Boolean = withContext(Dispatchers.Default) {
    BCrypt.verifyer().verify(password.toCharArray(), hash).verified
}
