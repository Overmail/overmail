package es.jvbabi.overmail.server.data.share

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The password a share link asks for, as it is stored and checked.
 *
 * PBKDF2-HMAC-SHA256 from the JDK, so this brings no dependency of its own. The people who see a
 * share link have no account here and there is nothing behind them but this hash, so the cost is
 * deliberately on the checking side: [ITERATIONS] is what makes guessing the password of a leaked
 * row expensive.
 *
 * [hash] is what goes into `Shares.passwordHash`; it carries the parameters it was made with, so a
 * row written under an older cost still verifies after this file changes.
 */
object SharePassword {

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 210_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_LENGTH_BYTES = 16

    /** Names the scheme in the stored string, so another one can be added beside it later. */
    private const val SCHEME = "pbkdf2-sha256"

    private val random = SecureRandom()
    private val encoder: Base64.Encoder = Base64.getEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getDecoder()

    /** `pbkdf2-sha256$<iterations>$<salt>$<key>`, both halves Base64. */
    fun hash(password: String): String {
        val salt = ByteArray(SALT_LENGTH_BYTES).also { random.nextBytes(it) }
        val key = derive(password, salt, ITERATIONS)
        return "$SCHEME\$$ITERATIONS\$${encoder.encodeToString(salt)}\$${encoder.encodeToString(key)}"
    }

    /**
     * Whether [password] is the one [hash] was made from.
     *
     * A stored string this cannot read is a no rather than a throw: it means the row was written
     * by something that is no longer here, and the answer to "may this visitor in" is still no.
     */
    fun verify(password: String, hash: String): Boolean {
        val parts = hash.split('$')
        if (parts.size != 4 || parts[0] != SCHEME) return false

        val iterations = parts[1].toIntOrNull() ?: return false
        val salt = runCatching { decoder.decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { decoder.decode(parts[3]) }.getOrNull() ?: return false

        val actual = derive(password, salt, iterations, expected.size * Byte.SIZE_BITS)
        // Constant time: a comparison that stops at the first wrong byte tells a caller how much
        // of a guess was right.
        return java.security.MessageDigest.isEqual(actual, expected)
    }

    private fun derive(
        password: String,
        salt: ByteArray,
        iterations: Int,
        lengthBits: Int = KEY_LENGTH_BITS,
    ): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, lengthBits)
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            // The spec keeps a copy of the characters; this is the only way to be rid of it.
            spec.clearPassword()
        }
    }
}
