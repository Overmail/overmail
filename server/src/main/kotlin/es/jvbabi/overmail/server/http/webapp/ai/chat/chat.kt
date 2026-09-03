package es.jvbabi.overmail.server.http.webapp.ai.chat

import es.jvbabi.overmail.server.auth.user
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.AiChat
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.di.dependencies
import kotlin.uuid.Uuid

suspend fun ApplicationCall.overmailDatabase(): OvermailDatabase =
    application.dependencies.resolve<OvermailDatabase>()

/**
 * The chat the `{chatId}` path segment points at, or null when there is none the signed-in user
 * may see. A chat of somebody else is a miss like an unknown id: telling the two apart would say
 * whether that chat exists.
 */
suspend fun ApplicationCall.resolveChatWithOwnerCheck(): AiChat? {
    val chatId = parameters["chatId"]?.let(Uuid::parseOrNull) ?: return null
    val userId = user.id.value

    return overmailDatabase().query {
        AiChat.findById(chatId)?.takeIf { chat -> chat.user.id.value == userId }
    }
}
