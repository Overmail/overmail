package es.jvbabi.overmail.server.http.api

import io.ktor.server.application.ApplicationCall
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Typed access to `?name=value`.
 *
 * Every one of these either answers a value or ends the request with 400 and the parameter named
 * in the error payload -- a handler that reads three parameters reads them as three lines instead
 * of three null checks that all respond the same way.
 *
 * A parameter that was not sent is not an error: it takes its default -- nothing this api reads
 * from the query string is mandatory.
 *
 * Each of them repeats the read instead of sharing one private helper. That is deliberate: the
 * OpenAPI compiler plugin inlines what a route handler calls, and the same helper reached several
 * times from one handler makes it fail in IR lowering.
 */
fun ApplicationCall.queryParameter(name: String): String? =
    request.queryParameters[name]?.trim()?.takeIf { it.isNotEmpty() }

/** A whole number, clamped into [range] rather than refused for being too large. */
fun ApplicationCall.intQueryParameter(name: String, default: Int, range: IntRange): Int {
    val raw = request.queryParameters[name]?.trim()?.takeIf { it.isNotEmpty() } ?: return default
    val value = raw.toIntOrNull() ?: invalidRequest(name, "is not a number", raw)
    return value.coerceIn(range)
}

/** A point in time as whole seconds since the epoch, which is how this api spells timestamps. */
fun ApplicationCall.instantQueryParameter(name: String): Instant? {
    val raw = request.queryParameters[name]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val seconds = raw.toLongOrNull()
        ?: invalidRequest(name, "is not a number of seconds since the epoch", raw)
    return Instant.fromEpochSeconds(seconds)
}

fun ApplicationCall.uuidQueryParameter(name: String): Uuid? {
    val raw = request.queryParameters[name]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return Uuid.parseOrNull(raw) ?: invalidRequest(name, "is not an id", raw)
}
