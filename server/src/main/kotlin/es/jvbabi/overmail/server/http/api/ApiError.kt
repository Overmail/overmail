package es.jvbabi.overmail.server.http.api

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What every failing request of this api answers with -- one shape, whether the caller sent a
 * malformed id, asked for somebody else's mail or hit a route that does not exist.
 *
 * [ApiErrorBody.Error.code] is the part a client branches on: the status says what kind of
 * failure it is, the code says which one, and [ApiErrorBody.Error.details] carries what it was
 * about (the parameter that was wrong, the resource that was missing). The message is for a
 * developer reading a log, not for a user reading a screen -- the frontend has its own wording.
 */
@Serializable
data class ApiErrorBody(
    @SerialName("error") val error: Error,
) {
    @Serializable
    data class Error(
        @SerialName("status") val status: Int,
        @SerialName("code") val code: String,
        @SerialName("message") val message: String,
        @SerialName("details") val details: Map<String, String> = emptyMap(),
    )
}

/** The codes [ApiErrorBody.Error.code] can carry. Kept small on purpose: a client branches on these. */
enum class ApiErrorCode(val wire: String) {
    /** No session, or one that no longer resolves to a user. */
    UNAUTHENTICATED("unauthenticated"),

    /** Signed in, but the resource belongs to somebody else. */
    FORBIDDEN("forbidden"),

    /** No such resource, and no such route. */
    NOT_FOUND("not_found"),

    /** A parameter or a body the server cannot work with. */
    INVALID_REQUEST("invalid_request"),

    /** The resource is not in a state this request can be applied to. */
    CONFLICT("conflict"),

    /** Anything that got out of a handler unhandled. */
    INTERNAL("internal"),
}

/**
 * A failure with an http status attached. Thrown from anywhere below a route and turned into
 * [ApiErrorBody] by the handler installed in `installApiErrorHandling`, so a handler stops at the
 * first thing that is wrong instead of threading a nullable through the rest of itself.
 *
 * Thrown out of a `query { }` block it rolls the transaction back, which is what a half-applied
 * write would otherwise leave behind.
 */
class ApiException(
    val status: HttpStatusCode,
    val code: ApiErrorCode,
    override val message: String,
    val details: Map<String, String> = emptyMap(),
) : RuntimeException(message) {

    fun toBody(): ApiErrorBody = ApiErrorBody(
        ApiErrorBody.Error(
            status = status.value,
            code = code.wire,
            message = message,
            details = details,
        )
    )
}

/**
 * Nobody is signed in.
 *
 * 401 rather than 403: the frontend sends a caller to /auth on this one, and a session that ran
 * out is exactly the case that has to land there. 403 is for a caller who *is* signed in, see
 * [forbidden].
 */
fun unauthenticated(): Nothing = throw ApiException(
    status = HttpStatusCode.Unauthorized,
    code = ApiErrorCode.UNAUTHENTICATED,
    message = "This request needs a signed-in user",
)

/** The caller is signed in, but [resource] is not theirs. */
fun forbidden(resource: String, id: String? = null): Nothing = throw ApiException(
    status = HttpStatusCode.Forbidden,
    code = ApiErrorCode.FORBIDDEN,
    message = "This $resource belongs to somebody else",
    details = buildMap {
        put("resource", resource)
        if (id != null) put("id", id)
    },
)

/** No [resource] under [id] -- an unknown one, a deleted one, or an id that is not an id. */
fun notFound(resource: String, id: String? = null): Nothing = throw ApiException(
    status = HttpStatusCode.NotFound,
    code = ApiErrorCode.NOT_FOUND,
    message = "No such $resource",
    details = buildMap {
        put("resource", resource)
        if (id != null) put("id", id)
    },
)

/** [parameter] cannot be worked with. [reason] says why, [value] is what was sent. */
fun invalidRequest(parameter: String, reason: String, value: String? = null): Nothing = throw ApiException(
    status = HttpStatusCode.BadRequest,
    code = ApiErrorCode.INVALID_REQUEST,
    message = "$parameter: $reason",
    details = buildMap {
        put("parameter", parameter)
        if (value != null) put("value", value)
    },
)

/** The resource is not in a state this request applies to, e.g. an answer that is still running. */
fun conflict(message: String, details: Map<String, String> = emptyMap()): Nothing = throw ApiException(
    status = HttpStatusCode.Conflict,
    code = ApiErrorCode.CONFLICT,
    message = message,
    details = details,
)
