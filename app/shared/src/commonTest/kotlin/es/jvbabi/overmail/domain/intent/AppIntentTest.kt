package es.jvbabi.overmail.domain.intent

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class AppIntentTest {

    private val openEmail = AppIntent.OpenEmail(accountId = Uuid.random(), emailId = Uuid.random())

    @Test
    fun `an intent survives the trip through its uri`() {
        val uri = openEmail.toUri()

        assertEquals("overmailapp://application/accounts/${openEmail.accountId}/emails/${openEmail.emailId}", uri)
        assertEquals(openEmail, AppIntent.parse(uri))
    }

    @Test
    fun `what else arrives under the scheme is not an intent`() {
        // The return from the sign-in.
        assertNull(AppIntent.parse("overmailapp://application"))
        assertNull(AppIntent.parse("overmailapp://application/accounts/${openEmail.accountId}"))
        assertNull(AppIntent.parse("overmailapp://application/accounts/nobody/emails/${openEmail.emailId}"))
        assertNull(AppIntent.parse("overmailapp://elsewhere/accounts/${openEmail.accountId}/emails/${openEmail.emailId}"))
        assertNull(AppIntent.parse("https://application/accounts/${openEmail.accountId}/emails/${openEmail.emailId}"))
        assertNull(AppIntent.parse("not a uri at all"))
    }

    @Test
    fun `an intent waits for whoever acts on it`() = runTest {
        val intents = AppIntents()

        assertTrue(intents.dispatch(openEmail.toUri()))
        assertFalse(intents.dispatch("overmailapp://application"))

        assertEquals(openEmail, intents.pending.first())
    }
}
