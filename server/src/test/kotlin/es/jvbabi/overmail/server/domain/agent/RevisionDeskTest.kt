package es.jvbabi.overmail.server.domain.agent

import es.jvbabi.overmail.server.domain.models.Email
import es.jvbabi.overmail.server.domain.models.EmailTag
import es.jvbabi.overmail.server.domain.models.MailParticipant
import es.jvbabi.overmail.server.domain.models.MailSummary
import es.jvbabi.overmail.server.domain.models.MailThread
import es.jvbabi.overmail.server.domain.models.MailThreadRef
import es.jvbabi.overmail.server.domain.models.Tag
import es.jvbabi.overmail.server.domain.models.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * What the revision desk does with the tools the model calls, and above all what it refuses.
 *
 * No model and no database: the tools are a function of the mailbox they are handed, and the rule
 * worth pinning down is the one the reader is promised -- the agent changes what the agent made and
 * nothing else. A prompt can ask for that; only this can hold it.
 */
class RevisionDeskTest {

    private val owner = User(
        id = Uuid.random(),
        username = "julius",
        email = "julius@example.org",
        name = "Julius Babies",
    )

    private val now = Clock.System.now()

    private val currentId = Uuid.random()
    private val earlierId = Uuid.random()

    private fun tag(name: String, byAgent: Boolean) = EmailTag(
        id = Uuid.random(),
        tag = Tag(
            id = Uuid.random(),
            user = owner,
            name = name,
            description = null,
            createdAt = now,
            createdByAgent = byAgent,
        ),
        reason = null,
        createdAt = now,
        createdByAgent = byAgent,
    )

    private fun summary(
        id: Uuid,
        subject: String,
        sent: Instant,
        tags: List<EmailTag>,
        thread: MailThreadRef? = null,
    ) = MailSummary(
        id = id,
        subject = subject,
        sent = sent,
        sender = MailParticipant(address = "jobs@musterfirma.test", name = "Musterfirma"),
        recipients = listOf(MailParticipant(address = owner.email, name = owner.name)),
        cc = emptyList(),
        bcc = emptyList(),
        isRead = true,
        isArchived = false,
        thread = thread,
        tags = tags,
    )

    private fun deskOf(
        emails: FakeEmails,
        tags: FakeTags,
        threads: FakeThreads,
        matters: FakeMatters = FakeMatters(),
        memories: FakeMemories = FakeMemories(),
        handles: MemoryHandles = MemoryHandles(),
    ) = RevisionDesk(
        owner = owner,
        mailId = currentId,
        sentAt = now,
        emails = emails,
        tagging = tags,
        threading = threads,
        matters = matters,
        remembering = memories,
        memories = handles,
    )

    private fun mailbox(
        currentTags: List<EmailTag> = listOf(tag("Bewerbung", byAgent = true)),
        currentThread: MailThreadRef? = null,
        earlierTags: List<EmailTag> = listOf(tag("Job", byAgent = true)),
    ): Triple<FakeEmails, FakeTags, FakeThreads> {
        val emails = FakeEmails(
            listOf(
                summary(currentId, "Ihre Bewerbung bei Musterfirma", now, currentTags, currentThread),
                summary(earlierId, "Eingangsbestaetigung", now - 3.days, earlierTags),
            )
        )

        return Triple(emails, FakeTags(), FakeThreads())
    }

    @Test
    fun `a mail with nothing to search on is not worth a run`() = runBlocking {
        val (emails, tags, threads) = mailbox(currentTags = emptyList())

        assertNull(deskOf(emails, tags, threads).briefing())
    }

    @Test
    fun `a mail that names a matter is worth a run even without tags`() = runBlocking {
        val (emails, tags, threads) = mailbox(currentTags = emptyList())
        val matters = FakeMatters()
        matters.record(currentId, "RE-2024-00123")

        val briefing = deskOf(emails, tags, threads, matters).briefing()

        // Off the mail's own record: the first mail of a matter has no thread to read it from.
        assertContains(briefing.orEmpty(), "RE-2024-00123")
    }

    @Test
    fun `a mail with a tag gets a briefing that names it`() = runBlocking {
        val (emails, tags, threads) = mailbox()
        val briefing = deskOf(emails, tags, threads).briefing()

        assertContains(briefing.orEmpty(), "M1")
        assertContains(briefing.orEmpty(), "Bewerbung (agent)")
    }

    @Test
    fun `the search lists earlier mail under its own handle`() = runBlocking {
        val (emails, tags, threads) = mailbox()
        tags.under = listOf(earlierId)

        val desk = deskOf(emails, tags, threads)
        desk.briefing()

        val answer = desk.run(
            "find_mails",
            buildJsonObject { put("tags", buildJsonArray { add(JsonPrimitive("Bewerbung")) }) },
        )

        assertFalse(answer.failed, answer.text)
        assertContains(answer.text, "M2")
        assertContains(answer.text, "Eingangsbestaetigung")
    }

    @Test
    fun `the mail being read is never listed as its own earlier company`() = runBlocking {
        val (emails, tags, threads) = mailbox()
        tags.under = listOf(currentId, earlierId)

        val desk = deskOf(emails, tags, threads)
        desk.briefing()

        val answer = desk.run("find_mails", buildJsonObject { put("tags", "Bewerbung") })

        assertFalse(answer.failed, answer.text)
        assertContains(answer.text, "1 earlier mail")
    }

    @Test
    fun `a search with nothing to go on is refused`() = runBlocking {
        val (emails, tags, threads) = mailbox()

        assertTrue(deskOf(emails, tags, threads).run("find_mails", JsonObject(emptyMap())).failed)
    }

    @Test
    fun `a handle nobody handed out is refused`() = runBlocking {
        val (emails, tags, threads) = mailbox()
        val desk = deskOf(emails, tags, threads)
        desk.briefing()

        assertTrue(desk.run("read_mail", buildJsonObject { put("mail", "M7") }).failed)
    }

    @Test
    fun `a tag the agent attached is taken off, a tag the reader attached is not`() = runBlocking {
        val (emails, tags, threads) = mailbox(
            currentTags = listOf(tag("Bewerbung", byAgent = true), tag("Wichtig", byAgent = false))
        )
        val desk = deskOf(emails, tags, threads)
        desk.briefing()

        val answer = desk.run(
            "set_tags",
            buildJsonObject {
                put("mail", "M1")
                put("tags", buildJsonArray { add(JsonPrimitive("Bewerbung Musterfirma")) })
                put("reason", "Gehoert zur laufenden Bewerbung bei Musterfirma.")
            },
        )

        assertFalse(answer.failed, answer.text)
        // The agent's own filing goes, the reader's stays, and the answer says which was which.
        assertEquals(listOf("Bewerbung"), tags.detached.map { it.tag.name })
        assertEquals(listOf("Bewerbung Musterfirma"), tags.attached.map { it.tag.name })
        assertContains(answer.text, "Wichtig")
    }

    @Test
    fun `a revision without a reason is refused`() = runBlocking {
        val (emails, tags, threads) = mailbox()
        val desk = deskOf(emails, tags, threads)
        desk.briefing()

        val answer = desk.run(
            "set_tags",
            buildJsonObject {
                put("mail", "M1")
                put("tags", buildJsonArray { add(JsonPrimitive("Job")) })
            },
        )

        assertTrue(answer.failed, answer.text)
        assertTrue(tags.attached.isEmpty())
    }

    @Test
    fun `a thread of one mail is not a matter`() = runBlocking {
        val (emails, tags, threads) = mailbox()
        val desk = deskOf(emails, tags, threads)
        desk.briefing()

        val answer = desk.run(
            "create_thread",
            buildJsonObject {
                put("title", "Bewerbung Musterfirma")
                put("mails", buildJsonArray { add(JsonPrimitive("M1")) })
                put("reason", "Gehoert zusammen.")
            },
        )

        assertTrue(answer.failed, answer.text)
        assertTrue(threads.created.isEmpty())
    }

    @Test
    fun `a thread it opened itself can be renamed`() = runBlocking {
        val (emails, tags, threads) = mailbox()
        tags.under = listOf(earlierId)

        val desk = deskOf(emails, tags, threads)
        desk.briefing()
        desk.run("find_mails", buildJsonObject { put("tags", "Bewerbung") })

        val opened = desk.run(
            "create_thread",
            buildJsonObject {
                put("title", "Bewerbung")
                put("mails", buildJsonArray {
                    add(JsonPrimitive("M1"))
                    add(JsonPrimitive("M2"))
                })
                put("reason", "Zwei Mails zur selben Bewerbung.")
            },
        )
        assertFalse(opened.failed, opened.text)

        val renamed = desk.run(
            "rename_thread",
            buildJsonObject {
                put("thread", "T1")
                put("title", "Bewerbung Musterfirma")
                put("reason", "Es geht nur um diese eine Bewerbung.")
            },
        )

        assertFalse(renamed.failed, renamed.text)
        assertEquals("Bewerbung Musterfirma", threads.renamed.single().second)
        assertTrue(desk.changes.any { it.contains("umbenannt") }, desk.changes.toString())
    }

    @Test
    fun `a thread the reader made is not renamed and not added to`() = runBlocking {
        val theirs = MailThread(
            id = Uuid.random(),
            user = owner,
            title = "Meine Bewerbungen",
            identifier = null,
            createdAt = now,
            createdByAgent = false,
        )
        val (emails, tags, threads) = mailbox(
            currentThread = MailThreadRef(id = theirs.id, title = theirs.title, size = 4)
        )
        threads.of[currentId] = theirs

        val desk = deskOf(emails, tags, threads)
        desk.briefing()

        val renamed = desk.run(
            "rename_thread",
            buildJsonObject {
                put("thread", "T1")
                put("title", "Bewerbung Musterfirma")
                put("reason", "Zu allgemein.")
            },
        )
        val added = desk.run(
            "add_to_thread",
            buildJsonObject {
                put("thread", "T1")
                put("mails", buildJsonArray { add(JsonPrimitive("M1")) })
                put("reason", "Gehoert dazu.")
            },
        )

        assertTrue(renamed.failed, renamed.text)
        assertTrue(added.failed, added.text)
        assertTrue(threads.renamed.isEmpty())
        assertTrue(threads.attached.isEmpty())
        assertTrue(desk.changes.isEmpty())
    }

    @Test
    fun `a tool nobody has is refused` () = runBlocking {
        val (emails, tags, threads) = mailbox()

        assertTrue(deskOf(emails, tags, threads).run("delete_mailbox", JsonObject(emptyMap())).failed)
    }
}
