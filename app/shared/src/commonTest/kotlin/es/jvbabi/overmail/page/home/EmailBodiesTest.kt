package es.jvbabi.overmail.page.home

import es.jvbabi.overmail.domain.model.EmailBody
import es.jvbabi.overmail.domain.model.OvermailAccount
import es.jvbabi.overmail.page.home.components.stack.StackCardBody
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

/** Renders nothing: until a test says otherwise, an html body stays waiting for its picture. */
private val NO_PICTURE: suspend (Uuid, String, () -> Int) -> ImageBitmap? = { _, _, _ -> awaitCancellation() }

class EmailBodiesTest {

    @Test
    fun loadingShowsUntilTheBodyIsThere() = runTest {
        val answer = CompletableDeferred<Result<EmailBody>>()
        val bodies = EmailBodies(backgroundScope, NO_PICTURE) { _, _ -> answer.await() }
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
        val bodies = EmailBodies(backgroundScope, NO_PICTURE) { _, _ -> Result.success(EmailBody(text = null, html = null)) }
        val id = Uuid.random()

        bodies.load(id, ACCOUNT)
        runCurrent()

        assertEquals(StackCardBody.Text(""), bodies.bodies.value[id])
    }

    @Test
    fun aBodyHereOrOnItsWayIsNotFetchedAgain() = runTest {
        val fetch = CountingFetch { Result.success(EmailBody(text = "plain", html = null)) }
        val bodies = EmailBodies(backgroundScope, NO_PICTURE, fetch::invoke)
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
        val bodies = EmailBodies(backgroundScope, NO_PICTURE, fetch::invoke)
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
    @Test
    fun anHtmlBodyIsPicturedRightAfterItIsHere() = runTest {
        val picture = FakePicture()
        val orders = mutableListOf<Int>()
        val bodies = EmailBodies(backgroundScope, { _, _, order -> orders += order(); picture }) { _, _ ->
            Result.success(EmailBody(text = null, html = "<p>html</p>"))
        }
        val id = Uuid.random()

        bodies.load(id, ACCOUNT, order = { 3 })
        runCurrent()

        assertEquals(StackCardBody.Html("<p>html</p>", picture), bodies.bodies.value[id])
        assertEquals(listOf(3), orders)
    }

    @Test
    fun aPictureThatWouldNotRenderIsAFailure() = runTest {
        val bodies = EmailBodies(backgroundScope, { _, _, _ -> null }) { _, _ ->
            Result.success(EmailBody(text = null, html = "<p>html</p>"))
        }
        val id = Uuid.random()

        bodies.load(id, ACCOUNT)
        runCurrent()

        assertEquals(StackCardBody.Failed, bodies.bodies.value[id])
    }

    @Test
    fun onlyThePicturesKeptAreHeldAndTheOthersAreMadeAgain() = runTest {
        var made = 0
        val bodies = EmailBodies(backgroundScope, { _, _, _ -> made++; FakePicture() }) { _, _ ->
            Result.success(EmailBody(text = null, html = "<p>html</p>"))
        }
        val kept = Uuid.random()
        val dropped = Uuid.random()
        bodies.load(kept, ACCOUNT)
        bodies.load(dropped, ACCOUNT)
        runCurrent()

        bodies.keepPictures(setOf(kept))
        assertNotNull((bodies.bodies.value[kept] as StackCardBody.Html).picture)
        assertEquals(StackCardBody.Html("<p>html</p>"), bodies.bodies.value[dropped])

        bodies.load(dropped, ACCOUNT)
        runCurrent()
        assertNotNull((bodies.bodies.value[dropped] as StackCardBody.Html).picture)
        assertEquals(3, made)
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

/** Stands in for a picture: a real bitmap needs the platform's graphics, which a host test has not. */
private class FakePicture : ImageBitmap {
    override val width = 1
    override val height = 1
    override val colorSpace = ColorSpaces.Srgb
    override val hasAlpha = true
    override val config = ImageBitmapConfig.Argb8888
    override fun readPixels(buffer: IntArray, startX: Int, startY: Int, width: Int, height: Int, bufferOffset: Int, stride: Int) = Unit
    override fun prepareToDraw() = Unit
}
