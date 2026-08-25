package es.jvbabi.overmail.server.http.filters

import es.jvbabi.overmail.server.domain.models.SpamFilter
import es.jvbabi.overmail.server.domain.spam.SpamRule
import es.jvbabi.overmail.server.domain.repository.EmailRepository
import es.jvbabi.overmail.server.domain.repository.SpamRepository
import es.jvbabi.overmail.server.http.ForbiddenException
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.resolve
import io.ktor.server.request.receive
import kotlinx.coroutines.flow.first
import kotlin.uuid.Uuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The wire shapes a filter goes out in and comes in as, shared by the routes below. */

/** One filter, as `GET /api/filters` and the two writing routes report it. */
@Serializable
data class FilterResponse(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    /** The tree itself, in the shape the editor builds and reads. */
    @SerialName("rule") val rule: SpamRule,
    @SerialName("is_active") val isActive: Boolean,
    /** ISO-8601, as mails carry their send time. */
    @SerialName("created_at") val createdAt: String,
)

/**
 * What a filter is written or overwritten with. The same shape for both, since a PUT replaces what
 * the filter says rather than patching it.
 */
@Serializable
data class FilterRequest(
    @SerialName("name") val name: String,
    @SerialName("rule") val rule: SpamRule,
    /** On unless the caller says otherwise: a filter is written to be used. */
    @SerialName("is_active") val isActive: Boolean = true,
    /**
     * The mail the filter was written for, if there is one. That mail is flagged as spam before
     * the filter exists -- the reader decided first and wrote the rule afterwards -- so saying so
     * here is what makes the filter the reason for it instead of the reader's own hand.
     */
    @SerialName("mail") val mail: String? = null,
)

internal fun SpamFilter.toResponse() = FilterResponse(
    id = id.toString(),
    name = name,
    rule = rule,
    isActive = isActive,
    createdAt = createdAt.toString(),
)

/**
 * The filter in the body, or a 400.
 *
 * A name is what the editor's dropdown offers a filter under, so an empty one would be a filter
 * nobody can pick out again.
 */
internal suspend fun ApplicationCall.receiveFilter(): FilterRequest {
    val request = runCatching { receive<FilterRequest>() }.getOrNull()
        ?: throw BadRequestException("The body is not a filter")

    if (request.name.isBlank()) throw BadRequestException("A filter needs a name")

    return request
}

/**
 * Hands the mail named in the request over to [filter] as the reason it counts as spam.
 *
 * Does nothing when the request named no mail, and nothing when the mail is not flagged: a filter
 * explains a flag, it does not set one. A mail of somebody else is a 403, as everywhere else.
 */
internal suspend fun ApplicationCall.attributeSpamTo(filter: SpamFilter, mail: String?) {
    val mailId = mail?.let {
        runCatching { Uuid.parse(it) }.getOrNull() ?: throw BadRequestException("`mail` is not a uuid")
    } ?: return

    val emailRepository = application.dependencies.resolve<EmailRepository>()
    val email = emailRepository.getById(mailId).first()
        ?: throw NotFoundException("No mail with id $mailId")
    if (email.imapAccount.user.id != filter.user.id) {
        throw ForbiddenException("Not the owner of mail $mailId")
    }

    application.dependencies.resolve<SpamRepository>().attributeToFilter(mailId, filter.id)
}
