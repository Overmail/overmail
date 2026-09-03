package es.jvbabi.overmail.server.ai.chat

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import es.jvbabi.overmail.server.database.models.AiChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ChatPromptTest {

    private val toolCall = AiChatMessage.MessageContent.AgentMessageContent.ToolCall(
        id = "call_1",
        tool = "read_email",
        arguments = """{"email_id":"abc"}""",
        result = """{"type":"email","subject":"Invoice 42"}""",
    )

    @Test
    fun `an earlier answer brings its tool calls back into the prompt`() {
        val prompt = chatPrompt(
            ChatTurn(
                chatId = kotlin.uuid.Uuid.random(),
                userId = kotlin.uuid.Uuid.random(),
                history = listOf(
                    ChatTurn.Message.User("Worum geht es in der Mail?"),
                    ChatTurn.Message.Agent(text = "Um eine Rechnung.", toolCalls = listOf(toolCall)),
                ),
                request = "Von wem war die noch mal?",
            )
        )

        // system, question, the call, its result, the answer -- the request itself is appended by
        // the graph's first node, not here.
        assertEquals(
            listOf(
                Message.System::class,
                Message.User::class,
                Message.Assistant::class,
                Message.User::class,
                Message.Assistant::class,
            ),
            prompt.messages.map { it::class },
        )

        val call = prompt.messages[2].parts.filterIsInstance<MessagePart.Tool.Call>().single()
        assertEquals("read_email", call.tool)
        assertEquals("call_1", call.id)
        assertEquals("""{"email_id":"abc"}""", call.args)

        val result = prompt.messages[3].parts.filterIsInstance<MessagePart.Tool.Result>().single()
        assertEquals("call_1", result.id)
        assertEquals(
            """{"type":"email","subject":"Invoice 42"}""",
            assertIs<MessagePart.Text>(result.parts.single()).text,
        )

        assertEquals("Um eine Rechnung.", prompt.messages[4].parts.filterIsInstance<MessagePart.Text>().single().text)
    }

    @Test
    fun `an answer that only called tools is still part of the history`() {
        val prompt = chatPrompt(
            ChatTurn(
                chatId = kotlin.uuid.Uuid.random(),
                userId = kotlin.uuid.Uuid.random(),
                history = listOf(ChatTurn.Message.Agent(text = "", toolCalls = listOf(toolCall))),
                request = "Und weiter?",
            )
        )

        assertEquals(
            listOf(Message.System::class, Message.Assistant::class, Message.User::class),
            prompt.messages.map { it::class },
        )
    }
}
