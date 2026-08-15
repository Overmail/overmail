package es.jvbabi.overmail.server.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.io.File
import java.security.SecureRandom
import java.util.Date
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

const val SESSION_COOKIE_NAME = "overmail_session"

private const val ISSUER = "overmail"
private const val AUDIENCE = "overmail-web"
private const val SECRET_FILE_NAME = "jwt-secret.key"

/**
 * Issues and verifies the session token that ends up in the browser cookie.
 *
 * The signing secret is generated into `data/jwt-secret.key` on first start rather than being
 * configured: it is machine state, not something anyone should have to invent by hand. Deleting
 * the file signs everybody out, which is the whole recovery procedure.
 */
class JwtService(storageDirectory: String = "./data") {

    private val algorithm = Algorithm.HMAC256(loadOrCreateSecret(storageDirectory))

    private val verifier = JWT.require(algorithm)
        .withIssuer(ISSUER)
        .withAudience(AUDIENCE)
        .build()

    fun issue(userId: Uuid, validFor: Duration = 30.days): String = JWT.create()
        .withIssuer(ISSUER)
        .withAudience(AUDIENCE)
        .withSubject(userId.toString())
        .withExpiresAt(Date(System.currentTimeMillis() + validFor.inWholeMilliseconds))
        .sign(algorithm)

    /** The user the token belongs to, or null if it is expired, forged or malformed. */
    fun userIdOf(token: String): Uuid? = runCatching { Uuid.parse(verifier.verify(token).subject) }.getOrNull()
}

private fun loadOrCreateSecret(storageDirectory: String): String {
    val file = File(storageDirectory).resolve(SECRET_FILE_NAME)
    if (!file.isFile) {
        file.parentFile?.mkdirs()
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        file.writeText(bytes.joinToString("") { "%02x".format(it) })
    }
    return file.readText()
}
