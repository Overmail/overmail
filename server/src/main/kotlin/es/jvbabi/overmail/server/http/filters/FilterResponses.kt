package es.jvbabi.overmail.server.http.filters

import es.jvbabi.overmail.server.domain.models.SpamFilter
import es.jvbabi.overmail.server.domain.spam.SpamRule
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
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
