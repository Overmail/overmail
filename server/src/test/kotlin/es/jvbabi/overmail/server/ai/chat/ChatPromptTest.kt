package es.jvbabi.overmail.server.ai.chat

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import es.jvbabi.overmail.server.ai.chat.tools.CreateLabelTool
import es.jvbabi.overmail.server.ai.chat.tools.DeleteKnowledgeTool
import es.jvbabi.overmail.server.ai.chat.tools.LabelEmailTool
import es.jvbabi.overmail.server.ai.chat.tools.ReadEmailTool
import es.jvbabi.overmail.server.ai.chat.tools.ReadKnowledgeTool
import es.jvbabi.overmail.server.ai.chat.tools.RenameChatTool
import es.jvbabi.overmail.server.ai.chat.tools.SearchEmailsTool
import es.jvbabi.overmail.server.ai.chat.tools.SearchKnowledgeTool
import es.jvbabi.overmail.server.ai.chat.tools.UnlabelEmailTool
import es.jvbabi.overmail.server.ai.chat.tools.WriteKnowledgeTool
import es.jvbabi.overmail.server.database.models.AiChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

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

    /** The text of the assistant messages a replayed [answer] ends up as, tool calls left out. */
    private fun replayedText(
        answer: String,
        toolCalls: List<AiChatMessage.MessageContent.AgentMessageContent.ToolCall> = emptyList(),
    ): List<String> = chatPrompt(
        ChatTurn(
            chatId = Uuid.random(),
            userId = Uuid.random(),
            history = listOf(
                ChatTurn.Message.User("Räum mein Wissen auf."),
                ChatTurn.Message.Agent(text = answer, toolCalls = toolCalls),
            ),
            request = "Und jetzt?",
        )
    ).messages
        .filterIsInstance<Message.Assistant>()
        .mapNotNull { message -> message.parts.filterIsInstance<MessagePart.Text>().singleOrNull()?.text }

    @Test
    fun `what the model reasoned is not replayed as something it said`() {
        val answer = ChatAgent.THINKING_START +
            ChatAgent.escapeThinking("Der Nutzer will <alles> löschen.\nIch rufe das Tool auf.") +
            ChatAgent.THINKING_END +
            "\n\nErledigt."

        assertEquals(listOf("Erledigt."), replayedText(answer))
    }

    @Test
    fun `reasoning a run ended in is dropped up to the end`() {
        val answer = "Ich schaue nach.\n\n" + ChatAgent.THINKING_START + "Zuerst die Suche, dann"

        assertEquals(listOf("Ich schaue nach."), replayedText(answer))
    }

    @Test
    fun `the markup of a tool call is not replayed, the remarks around it are`() {
        // The shape a run leaves behind: thinking, a remark, the element of each call in a block
        // of its own, the answer.
        val answer = listOf(
            ChatAgent.THINKING_START + "Zwei Einträge sind überholt." + ChatAgent.THINKING_END,
            "Ich lösche die beiden Einträge.",
            DeleteKnowledgeTool.markup("""Umzug "Berlin" > 2024"""),
            DeleteKnowledgeTool.markup("Alter Vertrag"),
            "Beide sind weg.",
        ).joinToString("\n\n")

        assertEquals(
            listOf("Ich lösche die beiden Einträge.\n\nBeide sind weg."),
            replayedText(answer, toolCalls = listOf(toolCall)),
        )
    }

    @Test
    fun `no tool leaves its markup in a replayed answer`() {
        val emailId = Uuid.random()
        val labelId = Uuid.random()

        // Every tool of the registry. They are told apart from what the model wrote by their
        // prefix alone, so one that is added without it would show up here.
        val markup = listOf(
            ReadEmailTool.markup(emailId, subject = """Re: "5 < 6" & more""", avatarUrl = null, avatarPadding = null),
            SearchEmailsTool.markup(subject = "Rechnung > 100 €", sender = null),
            CreateLabelTool.markup(labelId),
            LabelEmailTool.markup(emailId, labelId),
            UnlabelEmailTool.markup(emailId, labelId),
            SearchKnowledgeTool.markup("Umzug"),
            ReadKnowledgeTool.markup("Umzug"),
            WriteKnowledgeTool.markup("Umzug", replaced = true),
            DeleteKnowledgeTool.markup("Umzug"),
            RenameChatTool.markup("Wissen aufräumen"),
        )

        markup.forEach { element ->
            assertTrue(element.startsWith("<toolcall-"), "Not recognisable as a tool call: $element")
            assertEquals("", stripReplayMarkup(element), "Left behind by $element")
        }
    }

    @Test
    fun `the references the model wrote stay in a replayed answer`() {
        val answer = listOf(
            ReadEmailTool.markup(Uuid.random(), subject = "Invoice 42", avatarUrl = null, avatarPadding = null),
            """<email id="abc"></email> kam von <person id="def"></person> und trägt <label id="ghi"></label>.""",
        ).joinToString("\n\n")

        assertEquals(
            listOf("""<email id="abc"></email> kam von <person id="def"></person> und trägt <label id="ghi"></label>."""),
            replayedText(answer, toolCalls = listOf(toolCall)),
        )
    }

    @Test
    fun `an answer that was only markup is replayed as its tool calls alone`() {
        val answer = ChatAgent.THINKING_START + "Erst lesen." + ChatAgent.THINKING_END + "\n\n" +
            ReadEmailTool.markup(Uuid.random(), subject = "Invoice 42", avatarUrl = null, avatarPadding = null)

        val prompt = chatPrompt(
            ChatTurn(
                chatId = Uuid.random(),
                userId = Uuid.random(),
                history = listOf(ChatTurn.Message.Agent(text = answer, toolCalls = listOf(toolCall))),
                request = "Und weiter?",
            )
        )

        // system, the call, its result -- and no assistant text after them.
        assertEquals(
            listOf(Message.System::class, Message.Assistant::class, Message.User::class),
            prompt.messages.map { it::class },
        )
    }
}
