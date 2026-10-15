package es.jvbabi.overmail.server.auth

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.ApiKeys
import es.jvbabi.overmail.server.database.models.User
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * What every api key starts with. It is what tells a key from a session JWT in the same
 * `Authorization: Bearer` header, and what makes a leaked one recognisable to a secret scanner.
 */
const val API_KEY_PREFIX = "om_"

/** 32 random bytes: as much as a key needs, and far beyond guessing. */
private const val API_KEY_BYTES = 32

/** How much of a key the list shows -- the prefix and a few characters, never enough to use. */
private const val API_KEY_VISIBLE_LENGTH = 11

/** How often `last_used_at` is written: a script polling every second would write every second. */
private val LAST_USED_RESOLUTION = 1.minutes

private val random = SecureRandom()

/** A key as it is handed out once, and what of it is stored. */
data class NewApiKey(val key: String, val hash: String, val prefix: String)

/** Makes a key. Only [NewApiKey.hash] and [NewApiKey.prefix] go into the database. */
fun generateApiKey(): NewApiKey {
    val bytes = ByteArray(API_KEY_BYTES).also { random.nextBytes(it) }
    val key = API_KEY_PREFIX + Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)
    return NewApiKey(key = key, hash = hashApiKey(key), prefix = key.take(API_KEY_VISIBLE_LENGTH))
}

/**
 * SHA-256, hex. A plain hash and not a password hash on purpose: the key is random and long, so
 * there is nothing for a slow hash to protect, and it is checked on every request.
 */
fun hashApiKey(key: String): String =
    MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).toHexString()

/** Whether [token] is meant as an api key rather than a session. */
internal fun isApiKey(token: String): Boolean = token.startsWith(API_KEY_PREFIX)

/**
 * The user [key] belongs to, or null for a key that was deleted or never existed.
 *
 * Notes the use on the row, at most once per [LAST_USED_RESOLUTION] -- in the same transaction,
 * as an update that touches nothing while the last note is recent.
 */
internal suspend fun OvermailDatabase.apiKeyUser(key: String): User? = query {
    val hash = hashApiKey(key)
    val row = ApiKeys
        .select(ApiKeys.id, ApiKeys.user)
        .where { ApiKeys.keyHash eq hash }
        .firstOrNull()
        ?: return@query null

    val now = Clock.System.now()
    ApiKeys.update({
        (ApiKeys.id eq row[ApiKeys.id]) and
            (ApiKeys.lastUsedAt.isNull() or (ApiKeys.lastUsedAt less now - LAST_USED_RESOLUTION))
    }) { it[lastUsedAt] = now }

    User.findById(row[ApiKeys.user])
}
