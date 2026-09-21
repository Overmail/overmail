package es.jvbabi.overmail.data.network

import es.jvbabi.overmail.domain.model.NetworkErrorKind
import es.jvbabi.overmail.domain.model.NetworkException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Runs a request against the Overmail server and turns whatever goes wrong into a
 * [NetworkException], so a caller branches on a [NetworkErrorKind] instead of on engine types.
 *
 * Cancellation is passed through rather than reported: it is how a `withTimeout` or a closed
 * screen stops the request, and swallowing it would keep the coroutine running.
 */
suspend inline fun <T> safeRequest(block: () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: NetworkException) {
        Result.failure(e)
    } catch (e: ResponseException) {
        Result.failure(e.response.toNetworkException())
    } catch (e: IOException) {
        // Every engine's way of not getting through ends up here: unknown host, refused
        // connection, broken tls, the request timeout, the Darwin engine's NSError.
        Result.failure(NetworkException(NetworkErrorKind.ConnectionError, e.message, e))
    } catch (e: Exception) {
        Result.failure(NetworkException(NetworkErrorKind.Other, e.message, e))
    }
}

/**
 * What an unsuccessful (or unexpected) answer means. Only the server's own answers are read by
 * their status, see [isResponseFromBackend].
 */
suspend fun HttpResponse.toNetworkException(): NetworkException {
    if (!isResponseFromBackend()) {
        return when (status) {
            // The proxy is there, the server behind it is not: restarting, or down.
            HttpStatusCode.BadGateway,
            HttpStatusCode.ServiceUnavailable,
            HttpStatusCode.GatewayTimeout -> NetworkException(
                NetworkErrorKind.ConnectionError,
                "The server behind ${call.request.url.host} is not reachable (${status.value})",
            )
            else -> NetworkException(
                NetworkErrorKind.Other,
                "${call.request.url.host} answered ${status.value}, but not as an Overmail server",
            )
        }
    }

    val error = runCatching { errorJson.decodeFromString<ApiErrorBody>(bodyAsText()).error }.getOrNull()
    val kind = when {
        status == HttpStatusCode.Unauthorized -> NetworkErrorKind.Unauthorized
        status == HttpStatusCode.Forbidden -> NetworkErrorKind.Forbidden
        status == HttpStatusCode.NotFound -> NetworkErrorKind.NotFound
        status.value in 500..599 -> NetworkErrorKind.ServerError
        else -> NetworkErrorKind.Other
    }
    return NetworkException(
        kind = kind,
        message = error?.message ?: "The server answered ${status.value}",
        apiErrorCode = error?.code,
    )
}

/** The server's error payload, `http/api/ApiError.kt`. Only what the app reads of it. */
@Serializable
private data class ApiErrorBody(@SerialName("error") val error: Error) {
    @Serializable
    data class Error(
        @SerialName("code") val code: String,
        @SerialName("message") val message: String,
    )
}

private val errorJson = Json { ignoreUnknownKeys = true }
