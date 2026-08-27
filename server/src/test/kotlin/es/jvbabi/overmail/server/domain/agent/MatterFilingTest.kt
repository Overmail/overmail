package es.jvbabi.overmail.server.domain.agent

import es.jvbabi.overmail.server.domain.models.User
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * When a matter becomes a thread, which is the rule the whole per-mail identifier record exists for:
 * not with the first mail about it, but with the second.
 *
 * The tag is the other half and goes on straight away. A thread of one mail is clutter; a tag on one
 * mail is a label that is simply not shared yet, and it is the thing that makes the mail findable
 * in the meantime.
 */
class MatterFilingTest {

    private val owner = User(
        id = Uuid.random(),
        username = "julius",
        email = "julius@example.org",
        name = "Julius Babies",
    )

    private val first = Uuid.random()
    private val second = Uuid.random()
    private val third = Uuid.random()

    private val invoice = "RE-2024-00123"

    private fun filingOf(matters: FakeMatters, threads: FakeThreads, tags: FakeTags) =
        MatterFiling(owner = owner, matters = matters, threads = threads, tagging = tags)

    @Test
    fun `the first mail about a matter is written down and tagged, and gets no thread`() = runBlocking {
        val matters = FakeMatters()
        val threads = FakeThreads()
        val tags = FakeTags()

        val filed = filingOf(matters, threads, tags).file(first, invoice, "Rechnung")

        assertIs<MatterFiled.Noted>(filed)
        assertEquals(invoice, matters.of[first])
        assertEquals(listOf(invoice), tags.attached.map { it.tag.name })
        // Nothing to put in a thread but the one mail, so there is no thread.
        assertTrue(threads.created.isEmpty())
        assertTrue(threads.attached.isEmpty())
    }

    @Test
    fun `the second mail opens the thread and takes the first one into it`() = runBlocking {
        val matters = FakeMatters()
        val threads = FakeThreads()
        val tags = FakeTags()
        val filing = filingOf(matters, threads, tags)

        filing.file(first, invoice, "Rechnung")
        val filed = filing.file(second, invoice, "Rechnung")

        val opened = assertIs<MatterFiled.Opened>(filed)
        assertEquals(2, opened.mails)
        assertEquals("Rechnung $invoice", opened.thread.title)
        // Both of them, the older one first: a matter reads in the order it happened.
        assertEquals(listOf(first, second), threads.attached.map { it.first })
    }

    @Test
    fun `the third mail joins the thread that is already there`() = runBlocking {
        val matters = FakeMatters()
        val threads = FakeThreads()
        val tags = FakeTags()
        val filing = filingOf(matters, threads, tags)

        filing.file(first, invoice, "Rechnung")
        filing.file(second, invoice, "Rechnung")
        val filed = filing.file(third, invoice, "Rechnung")

        val joined = assertIs<MatterFiled.Joined>(filed)
        assertEquals(1, threads.created.size)
        assertEquals(listOf(third), threads.attached.filter { it.second == joined.thread.id }.map { it.first }.takeLast(1))
    }

    @Test
    fun `reading the same mail twice changes nothing`() = runBlocking {
        val matters = FakeMatters()
        val threads = FakeThreads()
        val tags = FakeTags()
        val filing = filingOf(matters, threads, tags)

        filing.file(first, invoice, "Rechnung")
        val again = filing.file(first, invoice, "Rechnung")

        // Still the first mail of the matter: a rerun is not a second mail.
        assertIs<MatterFiled.Noted>(again)
        assertTrue(threads.created.isEmpty())
    }

    @Test
    fun `every filing says why, in the reader's words`() = runBlocking {
        val matters = FakeMatters()
        val threads = FakeThreads()
        val tags = FakeTags()

        val filed = filingOf(matters, threads, tags).file(first, invoice, "Rechnung")

        assertContains(filed.reason, "Rechnung $invoice")
        assertEquals(filed.reason, tags.attached.single().reason)
    }
}
