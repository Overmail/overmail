package es.jvbabi.overmail.server.database.mappers

import es.jvbabi.overmail.server.ai.AgentLine
import es.jvbabi.overmail.server.ai.AgentRole
import es.jvbabi.overmail.server.ai.TokenUsage
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * That a stored run reads back as the run it was.
 *
 * The column is the only record of why a mailbox looks the way it does, so what matters here is the
 * round trip and the behaviour when it fails: a history that cannot be read gives up the history and
 * not the row.
 */
class StoredHistoryTest {

    private val run = listOf(
        AgentLine("sender", attempt = 1, role = AgentRole.SYSTEM, text = "Say where this mail is from."),
        AgentLine("sender", attempt = 1, role = AgentRole.USER, text = "From: GitHub <noreply@github.com>"),
        AgentLine(
            step = "sender",
            attempt = 1,
            role = AgentRole.ASSISTANT,
            text = """{"organisation":"GitHub"}""",
            usage = TokenUsage(input = 812, output = 17, reasoningCharacters = 240),
        ),
        AgentLine("revision", attempt = 2, role = AgentRole.TOOL_CALL, text = "find_mails {\"tags\":[\"Rechnung\"]}"),
        AgentLine("revision", attempt = 2, role = AgentRole.TOOL_RESULT, text = "No earlier mail."),
        AgentLine("revision", attempt = 2, role = AgentRole.ERROR, text = "the model did not answer"),
    )

    @Test
    fun `a run reads back line for line`() {
        assertEquals(run, storedHistory(run.asStoredHistory()))
    }

    @Test
    fun `the roles are stored as the words they go by`() {
        val stored = run.asStoredHistory()

        assertContains(stored, "\"role\":\"tool_call\"")
        assertContains(stored, "\"role\":\"tool_result\"")
    }

    @Test
    fun `a line that cost nothing carries no counts`() {
        val stored = listOf(run.first()).asStoredHistory()

        // Left out rather than written as null: what the backend did not report is not part of the
        // record.
        assertTrue(!stored.contains("usage"), stored)
    }

    @Test
    fun `a column that is not a history gives up the history, not the row`() {
        assertEquals(emptyList(), storedHistory("not json at all"))
        assertEquals(emptyList(), storedHistory(""))
    }

    @Test
    fun `a field added to a line later does not make older runs unreadable`() {
        val fromTheFuture =
            """[{"step":"sender","attempt":1,"role":"system","text":"x","mood":"cheerful"}]"""

        assertEquals(
            listOf(AgentLine("sender", attempt = 1, role = AgentRole.SYSTEM, text = "x")),
            storedHistory(fromTheFuture),
        )
    }
}
