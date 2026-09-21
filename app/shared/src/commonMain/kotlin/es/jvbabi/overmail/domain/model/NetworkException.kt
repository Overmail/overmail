package es.jvbabi.overmail.domain.model

/**
 * Why a request to the Overmail server failed, in the terms a screen can act on.
 *
 * [NotFound], [Unauthorized], [Forbidden] and [ServerError] are only ever the server's own
 * answers: the same status from anything in front of it (a proxy, a captive portal, werkbank's
 * login page) is [Other], since it says nothing about what was asked for.
 */
class NetworkException(
    val kind: NetworkErrorKind,
    message: String? = null,
    cause: Throwable? = null,
    /** The server's `error.code` (`not_found`, `conflict`, ...) when it sent one. */
    val apiErrorCode: String? = null,
) : Exception(message ?: kind.name, cause)

enum class NetworkErrorKind {
    /** The server could not be reached, not even through the proxy in front of it. */
    ConnectionError,
    Unauthorized,
    Forbidden,
    NotFound,
    ServerError,
    Other,
}
