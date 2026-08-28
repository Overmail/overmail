package es.jvbabi.overmail.server.domain.agent

import es.jvbabi.overmail.server.domain.models.Memory
import es.jvbabi.overmail.server.domain.models.User
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
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
 * What the mailbox knows about its reader: which of it is put in front of a model, under which
 * handle, and what the agent may do to it.
 *
 * The rule the whole shape exists for is the time span. A mailbox that knows forty things about
 * somebody must not spend its context on all of them to read one mail about a parcel -- and a degree
 * that ended in 2022 must not explain a mail from this week.
 */
class MemoryTest {

    private val owner = User(
        id = Uuid.random(),
        username = "julius",
        email = "julius@example.org",
        name = "Julius Babies",
    )

    private val now = Clock.System.now()
    private val mailId = Uuid.random()

    private fun memory(
        content: String,
        topic: String? = "Studium",
        from: Instant? = null,
        to: Instant? = null,
        parentId: Uuid? = null,
        byAgent: Boolean = true,
    ) = Memory(
        id = Uuid.random(),
        userId = owner.id,
        parentId = parentId,
        topic = topic,
        content = content,
        relevantFrom = from,
        relevantTo = to,
        learnedFromEmailId = null,
        createdAt = now,
        createdByAgent = byAgent,
    )

    // --- the time span -------------------------------------------------------------------------

    @Test
    fun `a memory with no dates covers every mail`() {
        val open = memory("Arbeitet als Werkstudent")

        assertTrue(open.isRelevantAt(now))
        assertTrue(open.isRelevantAt(now - 900.days))
    }

    @Test
    fun `a memory that has ended does not explain a later mail`() {
        val over = memory("Studiert an der TU Dresden", from = now - 900.days, to = now - 30.days)

        assertTrue(over.isRelevantAt(now - 100.days))
        assertFalse(over.isRelevantAt(now))
    }

    @Test
    fun `a memory does not explain the mail that came before it started`() {
        val later = memory("Wohnt in Leipzig", from = now - 10.days)

        assertFalse(later.isRelevantAt(now - 100.days))
        assertTrue(later.isRelevantAt(now))
    }

    @Test
    fun `an open end reads as still going on`() {
        assertEquals("seit ${(now - 10.days).toString().take(10)}", memory("x", from = now - 10.days).periodAsText())
        assertEquals("", memory("x").periodAsText())
    }

    // --- the handles ---------------------------------------------------------------------------

    @Test
    fun `core memories are numbered from one, in order`() {
        val handles = MemoryHandles(listOf(memory("Erstes"), memory("Zweites")))
        val lines = handles.lines()

        assertTrue(lines[0].startsWith("K1 · Studium · Erstes"), lines[0])
        assertTrue(lines[1].startsWith("K2 · Studium · Zweites"), lines[1])
    }

    @Test
    fun `a line says whose memory it is and what stretch it covers`() {
        val line = MemoryHandles(
            listOf(memory("Informatik an der TU Dresden", from = now - 400.days, byAgent = false))
        ).lines().single()

        assertContains(line, "(seit ${(now - 400.days).toString().take(10)})")
        assertContains(line, "(user)")
    }

    @Test
    fun `details are not lines, they are what a handle is for`() {
        val core = memory("Informatik an der TU Dresden")
        val handles = MemoryHandles(listOf(core, memory("Prüfungsamt: Frau Krause", topic = null, parentId = core.id)))

        assertEquals(1, handles.lines().size)
    }

    @Test
    fun `a handle nobody handed out names nothing`() {
        assertNull(MemoryHandles(listOf(memory("Erstes")))["K7"])
    }

    // --- the tools -----------------------------------------------------------------------------

    private fun deskWith(memories: FakeMemories, handles: MemoryHandles) = RevisionDesk(
        owner = owner,
        mailId = mailId,
        sentAt = now,
        emails = FakeEmails(emptyList()),
        tagging = FakeTags(),
        threading = FakeThreads(),
        matters = FakeMatters(),
        remembering = memories,
        memories = handles,
    )

    @Test
    fun `recall answers with what is behind a summary`() = runBlocking {
        val memories = FakeMemories()
        val core = memories.remember(
            user = owner,
            topic = "Studium",
            content = "Informatik an der TU Dresden",
            createdByAgent = true,
        )
        memories.remember(
            user = owner,
            topic = null,
            content = "Prüfungsamt ist Frau Krause",
            parentId = core.id,
            createdByAgent = true,
        )

        val answer = deskWith(memories, MemoryHandles(listOf(core)))
            .run("recall", buildJsonObject { put("memory", "K1") })

        assertFalse(answer.failed, answer.text)
        assertContains(answer.text, "Prüfungsamt ist Frau Krause")
    }

    @Test
    fun `recall says so where there is nothing behind it`() = runBlocking {
        val memories = FakeMemories()
        val core = memories.remember(owner, "Studium", "Informatik an der TU Dresden", createdByAgent = true)

        val answer = deskWith(memories, MemoryHandles(listOf(core)))
            .run("recall", buildJsonObject { put("memory", "K1") })

        assertFalse(answer.failed, answer.text)
        assertContains(answer.text, "Nothing further")
    }

    @Test
    fun `recall leaves out a detail that had already ended when the mail arrived`() = runBlocking {
        val memories = FakeMemories()
        val core = memories.remember(owner, "Arbeit", "Werkstudent bei Musterfirma", createdByAgent = true)
        memories.remember(
            user = owner,
            topic = null,
            content = "Team Zahlungsverkehr",
            parentId = core.id,
            relevantTo = now - 60.days,
            createdByAgent = true,
        )

        val answer = deskWith(memories, MemoryHandles(listOf(core)))
            .run("recall", buildJsonObject { put("memory", "K1") })

        assertFalse(answer.text.contains("Zahlungsverkehr"), answer.text)
    }

    @Test
    fun `something new is written down under its own handle`() = runBlocking {
        val memories = FakeMemories()
        val handles = MemoryHandles()

        val answer = deskWith(memories, handles).run(
            "remember",
            buildJsonObject {
                put("topic", "Studium")
                put("content", "Studiert Informatik an der TU Dresden")
                put("from", "2024-10")
            },
        )

        assertFalse(answer.failed, answer.text)
        val written = memories.kept.single()
        assertEquals("Studium", written.topic)
        assertEquals(mailId, written.learnedFromEmailId)
        // The month resolves to its first day, which is the honest reading of "seit Oktober 2024".
        assertEquals("2024-10-01", written.relevantFrom?.toString()?.take(10))
        assertContains(answer.text, "K1")
    }

    @Test
    fun `a detail goes under the thing it belongs to and gets no handle`() = runBlocking {
        val memories = FakeMemories()
        val core = memories.remember(owner, "Studium", "Informatik an der TU Dresden", createdByAgent = true)
        val handles = MemoryHandles(listOf(core))

        val answer = deskWith(memories, handles).run(
            "remember",
            buildJsonObject {
                put("of", "K1")
                put("content", "Immatrikuliert unter der Nummer 4711008")
            },
        )

        assertFalse(answer.failed, answer.text)
        assertEquals(core.id, memories.kept.last().parentId)
        assertEquals(1, handles.lines().size)
    }

    @Test
    fun `something new without a topic is refused`() = runBlocking {
        val memories = FakeMemories()

        val answer = deskWith(memories, MemoryHandles()).run(
            "remember",
            buildJsonObject { put("content", "Studiert irgendwas") },
        )

        assertTrue(answer.failed, answer.text)
        assertTrue(memories.kept.isEmpty())
    }

    @Test
    fun `a date that is not a date is refused rather than guessed`() = runBlocking {
        val memories = FakeMemories()

        val answer = deskWith(memories, MemoryHandles()).run(
            "remember",
            buildJsonObject {
                put("topic", "Studium")
                put("content", "Studiert Informatik")
                put("from", "letzten Herbst")
            },
        )

        assertTrue(answer.failed, answer.text)
        assertTrue(memories.kept.isEmpty())
    }

    @Test
    fun `a memory the reader wrote is not the agent's to end`() = runBlocking {
        val memories = FakeMemories()
        val theirs = memories.remember(
            user = owner,
            topic = "Studium",
            content = "Informatik an der TU Dresden",
            createdByAgent = false,
        )

        val answer = deskWith(memories, MemoryHandles(listOf(theirs)))
            .run("close_memory", buildJsonObject { put("memory", "K1"); put("on", "2026-03-31") })

        assertTrue(answer.failed, answer.text)
        assertNull(memories.kept.single().relevantTo)
    }

    @Test
    fun `its own memory is ended rather than deleted`() = runBlocking {
        val memories = FakeMemories()
        val mine = memories.remember(owner, "Arbeit", "Werkstudent bei Musterfirma", createdByAgent = true)
        val desk = deskWith(memories, MemoryHandles(listOf(mine)))

        val answer = desk.run(
            "close_memory",
            buildJsonObject { put("memory", "K1"); put("on", "2026-03-31") },
        )

        assertFalse(answer.failed, answer.text)
        // Still there, with an end on it: the mail of its own years is still read against it.
        assertEquals(1, memories.kept.size)
        assertEquals("2026-03-31", memories.kept.single().relevantTo?.toString()?.take(10))
        assertTrue(desk.changes.any { it.startsWith("Beendet") }, desk.changes.toString())
    }

    @Test
    fun `a memory too long to be a line is refused`() = runBlocking {
        val memories = FakeMemories()

        val answer = deskWith(memories, MemoryHandles()).run(
            "remember",
            buildJsonObject {
                put("topic", "Studium")
                put("content", "x".repeat(500))
            },
        )

        assertTrue(answer.failed, answer.text)
        assertTrue(memories.kept.isEmpty())
    }

    @Test
    fun `a tool call about a memory nobody listed is refused`() = runBlocking {
        val answer = deskWith(FakeMemories(), MemoryHandles())
            .run("recall", JsonObject(emptyMap()))

        assertTrue(answer.failed, answer.text)
    }
}
