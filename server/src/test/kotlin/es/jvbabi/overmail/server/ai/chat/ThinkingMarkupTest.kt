package es.jvbabi.overmail.server.ai.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class ThinkingMarkupTest {

    @Test
    fun `reasoning cannot start markup of its own`() {
        // A lone `>` starts nothing, so it stays as it is.
        assertEquals(
            "a &lt;b> &amp; c",
            ChatAgent.escapeThinking("a <b> & c"),
        )
    }

    @Test
    fun `a blank line inside would end the element, so it is collapsed`() {
        assertEquals(
            "erst dies\ndann das",
            ChatAgent.escapeThinking("erst dies\n\n   \ndann das"),
        )
    }

    @Test
    fun `a single line break stays a line break`() {
        assertEquals("eins\nzwei", ChatAgent.escapeThinking("eins\nzwei"))
    }
}
