package es.jvbabi.overmail.server.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** "12345678901234567890" in base32, the secret of the RFC 6238 test vectors. */
private const val RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

class TotpTest {

    private fun at(epochSeconds: Long) = object : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(epochSeconds)
    }

    @Test
    fun `agrees with the RFC test vector, so the secret is read as base32 like an app reads it`() {
        // RFC 6238, SHA1 at T=59: 94287082, of which an app shows the last six digits.
        assertTrue(verifyTotp(RFC_SECRET, "287082", at(59)))
    }

    @Test
    fun `a code one step off either way still counts, two steps do not`() {
        assertTrue(verifyTotp(RFC_SECRET, "287082", at(59 + 30)))
        assertTrue(verifyTotp(RFC_SECRET, "287082", at(59 - 30)))
        assertFalse(verifyTotp(RFC_SECRET, "287082", at(59 + 60)))
    }

    @Test
    fun `a wrong code does not`() {
        assertFalse(verifyTotp(RFC_SECRET, "000000", at(59)))
        assertFalse(verifyTotp(RFC_SECRET, "", at(59)))
    }

    @Test
    fun `a new secret is one the routes accept`() {
        assertTrue(TOTP_SECRET_PATTERN.matches(newTotpSecret()))
    }
}
