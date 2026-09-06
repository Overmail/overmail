package es.jvbabi.overmail.server.data.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a share link checks a visitor's password against. */
class SharePasswordTest {

    @Test
    fun `the password it was made from verifies, and nothing else does`() {
        val hash = SharePassword.hash("Projektgruppe 2026")

        assertTrue(SharePassword.verify("Projektgruppe 2026", hash))
        assertFalse(SharePassword.verify("projektgruppe 2026", hash))
        assertFalse(SharePassword.verify("Projektgruppe 2026 ", hash))
        assertFalse(SharePassword.verify("", hash))
    }

    @Test
    fun `the same password twice is two hashes, because each carries its own salt`() {
        val first = SharePassword.hash("hunter2")
        val second = SharePassword.hash("hunter2")

        assertTrue(first != second)
        assertTrue(SharePassword.verify("hunter2", first))
        assertTrue(SharePassword.verify("hunter2", second))
    }

    @Test
    fun `the stored string says how it was made, so the cost can change under it`() {
        val parts = SharePassword.hash("hunter2").split('$')

        assertEquals(4, parts.size)
        assertEquals("pbkdf2-sha256", parts[0])
        assertTrue(parts[1].toInt() >= 100_000)
    }

    @Test
    fun `a stored string this cannot read is a no, not a crash`() {
        assertFalse(SharePassword.verify("hunter2", ""))
        assertFalse(SharePassword.verify("hunter2", "hunter2"))
        assertFalse(SharePassword.verify("hunter2", "scrypt\$1\$aaaa\$bbbb"))
        assertFalse(SharePassword.verify("hunter2", "pbkdf2-sha256\$viele\$aaaa\$bbbb"))
        assertFalse(SharePassword.verify("hunter2", "pbkdf2-sha256\$1000\$!!!\$bbbb"))
    }
}
