package es.jvbabi.overmail.server.http.api

import es.jvbabi.overmail.server.database.models.AiChat
import es.jvbabi.overmail.server.database.models.AiChatMessage
import es.jvbabi.overmail.server.database.models.AiChatMessages
import es.jvbabi.overmail.server.database.models.AiChats
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.EmailAvatar
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.database.models.Label
import es.jvbabi.overmail.server.database.models.Labels
import es.jvbabi.overmail.server.database.models.User
import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select

/*
 * Everything this api addresses by a path parameter.
 *
 * Two functions per resource. `require<Resource>FromUrl()` is the resource or 404 -- a malformed
 * id, an unknown one and a deleted one are the same miss, because there is nothing a caller could
 * do with the difference. `requireOwned<Resource>FromUrl()` is that one plus 403 when it belongs
 * to somebody else.
 *
 * Both are answered once per request and kept on the call from there on, ownership included: a
 * handler that asks for the mail and then hands its id to a notifier costs one lookup.
 *
 * Written out per resource rather than through one generic lookup. The OpenAPI compiler plugin
 * walks into what a route handler calls to work out what it responds with, and it cannot lower a
 * generic function reached that way -- the build fails in IR lowering, not at runtime.
 */

/** Where each resolved resource is kept for the rest of the call. */
internal val URL_EMAIL = AttributeKey<Email>("overmail.url.email")
internal val URL_EMAIL_ID = AttributeKey<Uuid>("overmail.url.email-id")
internal val URL_LABEL = AttributeKey<Label>("overmail.url.label")
internal val URL_CHAT = AttributeKey<AiChat>("overmail.url.chat")
internal val URL_CHAT_MESSAGE = AttributeKey<AiChatMessage>("overmail.url.chat-message")

/** Which resources this call already established belong to the caller. */
internal val URL_OWNED = AttributeKey<MutableSet<String>>("overmail.url.owned")

/**
 * The id in [parameter], or 404.
 *
 * An id that is not one is a miss like an unknown one: telling the two apart only says which ids
 * exist. A route that has no such segment at all is a wiring mistake and says so -- answering 404
 * would hide it.
 */
internal fun ApplicationCall.idFromUrl(parameter: String, resource: String): Uuid {
    val raw = parameters[parameter]
        ?: error("this route has no {$parameter}, so its $resource cannot be resolved")
    return Uuid.parseOrNull(raw) ?: notFound(resource, raw)
}

/** Remembers that [resource] was checked against the caller, so a second question is free. */
internal fun ApplicationCall.rememberOwned(resource: String) {
    attributes.computeIfAbsent(URL_OWNED) { mutableSetOf() }.add(resource)
}

internal fun ApplicationCall.isKnownOwned(resource: String): Boolean =
    attributes.getOrNull(URL_OWNED)?.contains(resource) == true

/**
 * `{emailId}` as the stored mail.
 *
 * Loading a mail through the entity reads [Emails.rawContent] with it, which is the whole source
 * -- a handler that only needs to know *which* mail it is takes [requireOwnedEmailIdFromUrl].
 */
suspend fun ApplicationCall.requireEmailFromUrl(): Email {
    attributes.getOrNull(URL_EMAIL)?.let { return it }

    val id = idFromUrl("emailId", "email")
    val email = database().query { Email.findById(id) } ?: notFound("email", id.toString())

    attributes.put(URL_EMAIL, email)
    return email
}

/** [requireEmailFromUrl], and only when the mail is the caller's. */
suspend fun ApplicationCall.requireOwnedEmailFromUrl(): Email {
    val email = requireEmailFromUrl()
    if (isKnownOwned("email")) return email

    val owner = database().query { ownerOfEmail(email.id.value) }
    if (owner != requireAuthenticatedUser().id.value) forbidden("email", email.id.value.toString())

    rememberOwned("email")
    // The id lookup asks the same question, so let it find the answer here.
    attributes.put(URL_EMAIL_ID, email.id.value)
    return email
}

/**
 * `{emailId}` as its id, for the writes that touch one column.
 *
 * Owner and existence come out of one query over the columns, so nothing here reads the mail
 * source -- which is what separates this from [requireOwnedEmailFromUrl].
 */
suspend fun ApplicationCall.requireOwnedEmailIdFromUrl(): Uuid {
    attributes.getOrNull(URL_EMAIL_ID)?.let { return it }

    val id = idFromUrl("emailId", "email")
    val owner = database().query { ownerOfEmail(id) } ?: notFound("email", id.toString())
    if (owner != requireAuthenticatedUserId()) forbidden("email", id.toString())

    attributes.put(URL_EMAIL_ID, id)
    rememberOwned("email")
    return id
}

/** Who a mail belongs to: the account it was imported through decides, see [Emails.imapAccount]. */
internal fun ownerOfEmail(id: Uuid): User.Id? = Emails
    .innerJoin(ImapAccounts)
    .select(ImapAccounts.user)
    .where { Emails.id eq id }
    .singleOrNull()
    ?.get(ImapAccounts.user)
    ?.value

suspend fun ApplicationCall.requireLabelFromUrl(): Label {
    attributes.getOrNull(URL_LABEL)?.let { return it }

    val id = idFromUrl("labelId", "label")
    val label = database().query { Label.findById(id) } ?: notFound("label", id.toString())

    attributes.put(URL_LABEL, label)
    return label
}

suspend fun ApplicationCall.requireOwnedLabelFromUrl(): Label {
    val label = requireLabelFromUrl()
    if (isKnownOwned("label")) return label

    // The owner off the row that was just read rather than through `label.owner`, which would
    // load the whole user for an id that is already here.
    if (label.readValues[Labels.owner].value != requireAuthenticatedUserId()) {
        forbidden("label", label.id.value.toString())
    }

    rememberOwned("label")
    return label
}

suspend fun ApplicationCall.requireChatFromUrl(): AiChat {
    attributes.getOrNull(URL_CHAT)?.let { return it }

    val id = idFromUrl("chatId", "chat")
    val chat = database().query { AiChat.findById(id) } ?: notFound("chat", id.toString())

    attributes.put(URL_CHAT, chat)
    return chat
}

suspend fun ApplicationCall.requireOwnedChatFromUrl(): AiChat {
    val chat = requireChatFromUrl()
    if (isKnownOwned("chat")) return chat

    if (chat.readValues[AiChats.userId].value != requireAuthenticatedUserId()) {
        forbidden("chat", chat.id.value.toString())
    }

    rememberOwned("chat")
    return chat
}

/**
 * `{messageId}`, and only when it is a message of the `{chatId}` the same url names.
 *
 * A message id says nothing about who owns it, so the lookup goes through the chat -- which the
 * route above this one has usually resolved already.
 */
suspend fun ApplicationCall.requireChatMessageFromUrl(): AiChatMessage {
    attributes.getOrNull(URL_CHAT_MESSAGE)?.let { return it }

    val chat = requireChatFromUrl()
    val id = idFromUrl("messageId", "message")
    val message = database().query { AiChatMessage.findById(id) }
        ?.takeIf { it.readValues[AiChatMessages.chatId].value == chat.id.value }
        ?: notFound("message", id.toString())

    attributes.put(URL_CHAT_MESSAGE, message)
    return message
}

/** [requireChatMessageFromUrl], with the ownership of the chat it belongs to. */
suspend fun ApplicationCall.requireOwnedChatMessageFromUrl(): AiChatMessage {
    requireOwnedChatFromUrl()
    return requireChatMessageFromUrl()
}

/**
 * `{avatarId}` as the stored picture.
 *
 * Owned by nobody, so there is no owned variant: the same picture is shared by every address book
 * entry that resolved to it, the id says nothing about who corresponds with whom, and being signed
 * in is the whole check.
 */
suspend fun ApplicationCall.requireAvatarFromUrl(): EmailAvatar {
    val id = idFromUrl("avatarId", "avatar")
    return database().query { EmailAvatar.findById(id) } ?: notFound("avatar", id.toString())
}
