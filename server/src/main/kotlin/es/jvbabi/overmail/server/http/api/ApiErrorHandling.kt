package es.jvbabi.overmail.server.http.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json

/**
 * Turns everything that goes wrong below a route into [ApiErrorBody].
 *
 * Two sources feed it. [ApiException] is the one handlers raise themselves -- a missing resource,
 * one that is not the caller's, a parameter that is not a number. Everything else is a bug or a
 * malformed body, and it gets the same shape so a client never has to tell an error page from an
 * error payload.
 *
 * Statuses Ktor produces on its own (an unmatched route, a method the route does not have) are
 * given a body here as well: they arrive bare, and a client that parses one answer has to be able
 * to parse all of them.
 *
 * Tests that mount a route on their own `testApplication` install this too -- without it an
 * [ApiException] surfaces as a bare 500.
 */
fun Application.installApiErrorHandling() {
    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respondApiError(cause)
        }

        // Everything call.receive<T>() rejects: a body that is not json, one that is missing a
        // field, an id that is not a uuid.
        exception<BadRequestException> { call, cause ->
            call.respondApiError(
                ApiException(
                    status = HttpStatusCode.BadRequest,
                    code = ApiErrorCode.INVALID_REQUEST,
                    message = cause.message ?: "The request body could not be read",
                )
            )
        }

        exception<Throwable> { call, cause ->
            call.application.log.error("${call.request.httpMethod.value} ${call.request.path()} failed", cause)
            call.respondApiError(
                ApiException(
                    status = HttpStatusCode.InternalServerError,
                    code = ApiErrorCode.INTERNAL,
                    message = "The server could not handle this request",
                )
            )
        }

        status(HttpStatusCode.NotFound) { call, _ ->
            call.respondApiError(
                ApiException(
                    status = HttpStatusCode.NotFound,
                    code = ApiErrorCode.NOT_FOUND,
                    message = "No such route",
                    details = mapOf("path" to call.request.path()),
                )
            )
        }

        status(HttpStatusCode.MethodNotAllowed) { call, _ ->
            call.respondApiError(
                ApiException(
                    status = HttpStatusCode.MethodNotAllowed,
                    code = ApiErrorCode.INVALID_REQUEST,
                    message = "This route does not answer ${call.request.httpMethod.value}",
                    details = mapOf("path" to call.request.path(), "method" to call.request.httpMethod.value),
                )
            )
        }
    }
}

private val json = Json { encodeDefaults = true }

/**
 * Answers with the error payload, unless the response already went out -- a socket that upgraded
 * or a stream that started has nothing left to put a status on.
 *
 * Serialized here rather than handed to ContentNegotiation: an error has to come out as json even
 * when the failure was the negotiation itself, and a 406 instead of the 404 that caused it tells
 * a client nothing.
 */
suspend fun ApplicationCall.respondApiError(error: ApiException) {
    if (response.isCommitted) return
    respondText(
        text = json.encodeToString(error.toBody()),
        contentType = ContentType.Application.Json,
        status = error.status,
    )
}
