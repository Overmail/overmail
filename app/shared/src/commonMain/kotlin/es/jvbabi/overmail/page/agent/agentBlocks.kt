package es.jvbabi.overmail.page.agent

/**
 * An answer, cut at the tool call elements the agent wrote into it; the web app's
 * `agentBlocks.ts`. Consecutive tool calls are one block, shown as one collapsible group.
 */
sealed class AgentBlock {
    data class Text(val content: String) : AgentBlock()

    data class Tools(val calls: List<ToolCall>) : AgentBlock()
}

data class ToolCall(
    /** The element name without the `toolcall-` prefix: `read-email`, `search-emails`, ... */
    val kind: String,
    /** Decoded, by their name as the server writes it (`emailId`, `labelId`, ...). */
    val attributes: Map<String, String> = emptyMap(),
    /** What stood inside the element; only thinking uses it. */
    val content: String = "",
) {
    fun attribute(name: String): String? = attributes[name]?.takeIf { it.isNotEmpty() }
}

// Our own markup: an opening tag with quoted attributes, and either a closing tag or -- while the
// answer is still streaming -- nothing yet.
private const val THINKING = "thinking"

private val TOOL_CALL = Regex("""<toolcall-([a-z-]+)((?:\s+[a-zA-Z-]+="[^"]*")*)\s*>([\s\S]*?)(?:</toolcall-\1>|$)""")
private val ATTRIBUTE = Regex("""([a-zA-Z-]+)="([^"]*)"""")

/** The entities the server escapes when it writes markup, see `escapeAttribute`. */
private val ENTITIES = listOf(
    "&quot;" to "\"",
    "&lt;" to "<",
    "&gt;" to ">",
    // Last: `&amp;lt;` has to come out as `&lt;`, not as `<`.
    "&amp;" to "&",
)

private fun decode(value: String): String =
    ENTITIES.fold(value) { decoded, (entity, character) -> decoded.replace(entity, character) }

fun agentBlocks(content: String): List<AgentBlock> {
    val blocks = mutableListOf<AgentBlock>()
    var index = 0

    fun addText(text: String) {
        // The blank lines around an element are what separates it as a block; they are not part
        // of the text around it.
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) blocks += AgentBlock.Text(trimmed)
    }

    fun addToolCall(call: ToolCall) {
        // Only calls that follow each other directly belong in the same group; text in between
        // starts a new one.
        val last = blocks.lastOrNull()
        if (last !is AgentBlock.Tools) {
            blocks += AgentBlock.Tools(listOf(call))
            return
        }
        // The server can write one reasoning in many pieces, an element per streamed delta: they
        // are one thought. Joined as they are, since a piece carries the space before its words.
        val previous = last.calls.last()
        blocks[blocks.lastIndex] = if (call.kind == THINKING && previous.kind == THINKING) {
            AgentBlock.Tools(last.calls.dropLast(1) + previous.copy(content = previous.content + call.content))
        } else {
            AgentBlock.Tools(last.calls + call)
        }
    }

    TOOL_CALL.findAll(content).forEach { match ->
        addText(content.substring(index, match.range.first))
        addToolCall(
            ToolCall(
                kind = match.groupValues[1],
                attributes = ATTRIBUTE.findAll(match.groupValues[2])
                    .associate { it.groupValues[1] to decode(it.groupValues[2]) },
                // Trimmed once the pieces of a thought are joined, see addToolCall.
                content = decode(match.groupValues[3]),
            )
        )
        index = match.range.last + 1
    }
    addText(content.substring(index))

    return blocks.map { block ->
        if (block is AgentBlock.Tools) AgentBlock.Tools(block.calls.map { it.copy(content = it.content.trim()) })
        else block
    }
}
