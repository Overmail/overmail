package es.jvbabi.overmail.server.auth

import dev.turingcomplete.kotlinonetimepassword.GoogleAuthenticator
import java.security.MessageDigest
import java.util.Date
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

private const val ISSUER = "Overmail"
private val TIME_STEP = 30.seconds

/** What a secret looks like on the wire: base32, as authenticator apps take it. */
val TOTP_SECRET_PATTERN = Regex("^[A-Z2-7]{16,64}$")

/** A fresh base32 secret, 160 bits, the size RFC 4226 recommends. */
fun newTotpSecret(): String = GoogleAuthenticator.createRandomSecret()

/** What the QR code carries: `otpauth://totp/Overmail:<account>?secret=…&issuer=Overmail`. */
fun totpUri(secret: String, account: String): String =
    GoogleAuthenticator(secret).otpAuthUriBuilder()
        .label(account, ISSUER)
        .issuer(ISSUER)
        .buildToString()

/**
 * Whether [code] is the one [secret] gives now, or one step before or after it: a phone's clock is
 * rarely to the second, and a code typed in the last seconds of its window arrives in the next.
 *
 * Not authentikt's own `getSecret`: that one takes the bytes of the base32 text rather than
 * decoding it, so no authenticator app would agree with it, and it allows no drift.
 */
fun verifyTotp(secret: String, code: String, clock: Clock = Clock.System): Boolean {
    val candidate = code.trim()
    val authenticator = GoogleAuthenticator(secret)
    val now = clock.now()
    // Every window is compared, in constant time, so the answer takes as long either way.
    return listOf(now - TIME_STEP, now, now + TIME_STEP)
        .map { authenticator.generate(Date(it.toEpochMilliseconds())) }
        .fold(false) { matched, expected ->
            MessageDigest.isEqual(expected.toByteArray(), candidate.toByteArray()) or matched
        }
}
