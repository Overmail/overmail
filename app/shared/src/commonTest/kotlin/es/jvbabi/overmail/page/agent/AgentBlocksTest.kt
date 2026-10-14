package es.jvbabi.overmail.page.agent

import kotlin.test.Test
import kotlin.test.assertEquals

class AgentBlocksTest {
    @Test
    fun textWithoutToolCallsIsOneBlock() {
        assertEquals(listOf(AgentBlock.Text("Hello")), agentBlocks("  Hello\n"))
    }

    @Test
    fun consecutiveToolCallsAreOneGroup() {
        val content = """
            Let me look.

            <toolcall-search-emails subject="rent &amp; deposit" sender=""></toolcall-search-emails>

            <toolcall-read-email emailId="1" subject="&quot;Hi&quot;"></toolcall-read-email>

            Found two.
        """.trimIndent()

        assertEquals(
            listOf(
                AgentBlock.Text("Let me look."),
                AgentBlock.Tools(
                    listOf(
                        ToolCall("search-emails", mapOf("subject" to "rent & deposit", "sender" to "")),
                        ToolCall("read-email", mapOf("emailId" to "1", "subject" to "\"Hi\"")),
                    )
                ),
                AgentBlock.Text("Found two."),
            ),
            agentBlocks(content),
        )
    }

    @Test
    fun textBetweenToolCallsStartsANewGroup() {
        val blocks = agentBlocks("<toolcall-thinking>a</toolcall-thinking> then <toolcall-thinking>b</toolcall-thinking>")
        assertEquals(3, blocks.size)
    }

    @Test
    fun aThoughtWrittenInPiecesIsOneCall() {
        // What the server stores when every streamed delta becomes an element of its own.
        val content = "<toolcall-thinking>The</toolcall-thinking>\n\n" +
            "<toolcall-thinking> user wrote</toolcall-thinking>\n\n" +
            "<toolcall-thinking> \"Programm</toolcall-thinking>\n\n" +
            "<toolcall-search-emails subject=\"Programm\"></toolcall-search-emails>\n\n" +
            "<toolcall-thinking>Found it</toolcall-thinking>"

        assertEquals(
            listOf(
                AgentBlock.Tools(
                    listOf(
                        ToolCall("thinking", content = "The user wrote \"Programm"),
                        ToolCall("search-emails", mapOf("subject" to "Programm")),
                        ToolCall("thinking", content = "Found it"),
                    )
                )
            ),
            agentBlocks(content),
        )
    }

    @Test
    fun anUnclosedToolCallWhileStreamingTakesTheRest() {
        assertEquals(
            listOf(AgentBlock.Text("Hm"), AgentBlock.Tools(listOf(ToolCall("thinking", content = "about the")))),
            agentBlocks("Hm <toolcall-thinking>about the"),
        )
    }
}
