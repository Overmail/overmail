package es.jvbabi.overmail.server.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What [answerJson] gets out of the things a model actually answers with.
 *
 * The cases are answers that were once thrown away as the wrong shape: reasoning in front of the
 * object, a closing tag whose opening one the backend kept, a sentence introducing the JSON. Plus
 * the ones a brace count has to get right to be worth having at all -- a brace inside a string, an
 * object inside the object, an answer that stops halfway.
 */
class AnswerJsonTest {

    @Test
    fun `an answer that is only the object is the object`() {
        val json = """{"person":"Julius Babies","organisation":null}"""

        assertEquals(json, answerJson(json))
    }

    @Test
    fun `whatever the model thought before answering is dropped`() {
        val answer = """
            <think>
            The signature says Julius Babies, so that is the person. No company anywhere.
            </think>
            {"person":"Julius Babies","organisation":null}
        """.trimIndent()

        assertEquals("""{"person":"Julius Babies","organisation":null}""", answerJson(answer))
    }

    @Test
    fun `a closing tag with nothing that opened it is dropped too`() {
        val answer = """
            The mail is from a shop, so no platform.
            </think>
            {"via":null}
        """.trimIndent()

        assertEquals("""{"via":null}""", answerJson(answer))
    }

    @Test
    fun `thinking twice is thinking, both times`() {
        val answer = "<think>Who wrote it?</think>Let me check the footer.<think>GitHub.</think>" +
            """{"via":"GitHub"}"""

        assertEquals("""{"via":"GitHub"}""", answerJson(answer))
    }

    @Test
    fun `reasoning the model never marked as any is dropped as well`() {
        val answer = """
            Here is the JSON you asked for:
            {"organisation":"Deutsche Bahn"}
            Let me know if you need anything else.
        """.trimIndent()

        assertEquals("""{"organisation":"Deutsche Bahn"}""", answerJson(answer))
    }

    @Test
    fun `a code fence around the object is not part of the object`() {
        val answer = "```json\n{\"person\":null}\n```"

        assertEquals("""{"person":null}""", answerJson(answer))
    }

    @Test
    fun `a brace inside a string does not end the object`() {
        val json = """{"context":["gh:acme/widgets#412}"],"via":"GitHub"}"""

        assertEquals(json, answerJson(json))
    }

    @Test
    fun `an escaped quote does not end the string it is in`() {
        val json = """{"organisation":"Kaffee \"GmbH\"}","via":null}"""

        assertEquals(json, answerJson(json))
    }

    @Test
    fun `an object inside the object closes with the outer one`() {
        val json = """{"provider":{"name":"GitHub","kind":"code"},"valid_for_minutes":10}"""

        assertEquals(json, answerJson(json))
    }

    @Test
    fun `an answer that stops halfway through the object is no answer`() {
        assertNull(answerJson("""<think>ok</think>{"person":"Julius Bab"""))
    }

    @Test
    fun `an answer that is thinking and nothing else is no answer`() {
        assertNull(answerJson("<think>The sender is a shop. Or a school? Hard to say.</think>"))
    }

    @Test
    fun `an answer in prose alone is no answer`() {
        assertNull(answerJson("This mail appears to be from Deutsche Bahn."))
    }
}
