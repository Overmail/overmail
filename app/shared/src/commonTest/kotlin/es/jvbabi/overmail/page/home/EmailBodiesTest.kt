package es.jvbabi.overmail.page.home

import es.jvbabi.overmail.domain.model.EmailBody
import es.jvbabi.overmail.domain.model.OvermailAccount
import es.jvbabi.overmail.page.home.components.stack.StackCardBody
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

private val ACCOUNT = OvermailAccount(
    id = Uuid.random(),
    username = "ada",
    firstName = "Ada",
    lastName = "Lovelace",
    email = "ada@example.com",
    homeserver = "https://example.com",
    token = "token",
)

class EmailBodiesTest {

    @Test
    fun loadingShowsUntilTheBodyIsThere() = runTest {
        val answer = CompletableDeferred<Result<EmailBody>>()
        val bodies = EmailBodies(backgroundScope) { _, _ -> answer.await() }
        val id = Uuid.random()

        bodies.load(id, ACCOUNT)
        runCurrent()
        assertEquals(StackCardBody.Loading, bodies.bodies.value[id])

        answer.complete(Result.success(EmailBody(text = "plain", html = "<p>html</p>")))
        runCurrent()
        assertEquals(StackCardBody.Html("<p>html</p>"), bodies.bodies.value[id])
    }

    @Test
    fun mailWithoutHtmlIsText() = runTest {
        val bodies = EmailBodies(backgroundScope) { _, _ -> Result.success(EmailBody(text = null, html = null)) }
        val id = Uuid.random()

        bodies.load(id, ACCOUNT)
        runCurrent()

        assertEquals(StackCardBody.Text(""), bodies.bodies.value[id])
    }

    @Test
    fun aBodyHereOrOnItsWayIsNotFetchedAgain() = runTest {
        val fetch = CountingFetch { Result.success(EmailBody(text = "plain", html = null)) }
        val bodies = EmailBodies(backgroundScope, fetch::invoke)
        val id = Uuid.random()

        bodies.load(id, ACCOUNT)
        bodies.load(id, ACCOUNT)
        runCurrent()
        bodies.load(id, ACCOUNT)
        runCurrent()

        assertEquals(1, fetch.calls)
    }

    @Test
    fun aFailedBodyIsAskedForAgain() = runTest {
        var fail = true
        val fetch = CountingFetch {
            if (fail) Result.failure(IllegalStateException("offline")) else Result.success(EmailBody(text = "plain", html = null))
        }
        val bodies = EmailBodies(backgroundScope, fetch::invoke)
        val id = Uuid.random()

        bodies.load(id, ACCOUNT)
        runCurrent()
        assertEquals(StackCardBody.Failed, bodies.bodies.value[id])

        fail = false
        bodies.load(id, ACCOUNT)
        runCurrent()
        assertEquals(StackCardBody.Text("plain"), bodies.bodies.value[id])
        assertEquals(2, fetch.calls)
    }
}

private class CountingFetch(private val answer: () -> Result<EmailBody>) {
    var calls = 0

    @Suppress("UNUSED_PARAMETER")
    suspend fun invoke(emailId: Uuid, account: OvermailAccount): Result<EmailBody> {
        calls++
        return answer()
    }
}
