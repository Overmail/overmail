package es.jvbabi.overmail.server.ai.chat.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import es.jvbabi.overmail.server.ai.chat.cleanChatName
import es.jvbabi.overmail.server.data.notifier.AiChatNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.AiChat
import es.jvbabi.overmail.server.database.models.User
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Renames the chat the agent is answering in.
 *
 * A chat is named once, from its first exchange, and that name is a guess made before the
 * conversation had anywhere to go. This is the way back: the agent may rename the chat when the
 * name stopped fitting -- but only once per answer, which is what [renamed] enforces, so a model
 * that likes the sound of it cannot spend a run retitling.
 *
 * There is no chat id to pass: it is the chat this run belongs to, so nothing the model says can
 * rename another one.
 */
class RenameChatTool(
    private val userId: User.Id,
    private val chatId: AiChat.Id,
    private val database: OvermailDatabase,
    private val chatNotifier: AiChatNotifier,
    /** Called with the markup for a rename that happened. Not called for a name that was already there. */
    private val onRenamed: (String) -> Unit = {},
) : Tool<RenameChatTool.Args, RenameChatTool.Result>(
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    name = NAME,
    description = "Rename the chat you are answering in. Optional, and at most once per answer: " +
        "use it when the name no longer says what this chat is about -- the conversation moved " +
        "on, or the chat was named before that was clear -- and when the user asks for a name. " +
        "A chat without a name is named on its own afterwards, so this is not needed for a new " +
        "one, and a name the user wrote themselves stays unless they ask you to change it.",
) {

    /** One rename per run, and the tool is built per run. */
    private val renamed = AtomicBoolean(false)

    @Serializable
    data class Args(
        @property:LLMDescription(
            "The new name, in the language of the user: a short phrase of at most five words " +
                "naming what the chat is about, without quotes, markup or a final full stop."
        )
        @SerialName("name") val name: String,
    )

    @Serializable
    sealed class Result {

        @Serializable
        @SerialName("renamed")
        data class Renamed(
            /** The name as it was written: the cleanup can shorten what the model asked for. */
            @SerialName("name") val name: String,
            /** True when the chat already had this name, so nothing changed. */
            @SerialName("already_named") val alreadyNamed: Boolean,
        ) : Result()

        @Serializable
        @SerialName("invalid_argument")
        data class InvalidArgument(
            @SerialName("message") val message: String,
        ) : Result()
    }

    override suspend fun execute(args: Args): Result {
        val name = cleanChatName(args.name)
            ?: return Result.InvalidArgument("A chat name has to be text; this one was empty.")

        if (!renamed.compareAndSet(false, true)) {
            return Result.InvalidArgument("You already renamed this chat in this answer; it keeps that name.")
        }

        // Read back inside the transaction that writes it: the client is told about the new
        // name right after, and an entity of a transaction that is over cannot be written.
        val outcome = database.query {
            val chat = AiChat.findById(chatId) ?: return@query null
            val alreadyNamed = chat.name == name
            if (!alreadyNamed) chat.name = name
            chat to alreadyNamed
        } ?: return Result.InvalidArgument("This chat no longer exists.")

        val (chat, alreadyNamed) = outcome

        if (!alreadyNamed) {
            onRenamed(markup(name))
            chatNotifier.notifyChatUpsert(userId = userId, chat = chat)
        }

        return Result.Renamed(name = name, alreadyNamed = alreadyNamed)
    }

    companion object {
        const val NAME = "rename_chat"

        /**
         * The name itself, not an id: unlike a label, a chat name is not looked up when the answer
         * is shown, and the line is about what the name became at that point.
         */
        fun markup(name: String): String =
            """<toolcall-rename-chat name="${escapeAttribute(name)}"></toolcall-rename-chat>"""
    }
}
