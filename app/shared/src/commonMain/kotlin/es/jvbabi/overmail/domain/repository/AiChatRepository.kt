package es.jvbabi.overmail.domain.repository

import es.jvbabi.overmail.domain.model.AiAnswerEvent
import es.jvbabi.overmail.domain.model.AiChat
import es.jvbabi.overmail.domain.model.AiChatMessage
import es.jvbabi.overmail.domain.model.AiChatSent
import es.jvbabi.overmail.domain.model.OvermailAccount
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

/** The assistant's chats. Nothing of it is stored on the device; it all lives on the server. */
interface AiChatRepository {
    /**
     * The newest chats of [user], newest first, and again whenever one is created, renamed or
     * deleted, for as long as it is collected.
     */
    fun getChats(user: OvermailAccount): Flow<List<AiChat>>

    /** Every message of [chatId], oldest first. */
    suspend fun getMessages(chatId: Uuid, user: OvermailAccount): Result<List<AiChatMessage>>

    /** Asks [text] in [chatId], or in a new chat when it is null. */
    suspend fun send(chatId: Uuid?, text: String, user: OvermailAccount): Result<AiChatSent>

    /**
     * Writes the answer [messageId] anew. The old one is dropped on the server, and the message is
     * pending again until the new one is written.
     */
    suspend fun retry(chatId: Uuid, messageId: Uuid, user: OvermailAccount): Result<Unit>

    /** The answer [messageId] as it is written, until it is [AiAnswerEvent.Done]. */
    fun followAnswer(chatId: Uuid, messageId: Uuid, user: OvermailAccount): Flow<AiAnswerEvent>
}
