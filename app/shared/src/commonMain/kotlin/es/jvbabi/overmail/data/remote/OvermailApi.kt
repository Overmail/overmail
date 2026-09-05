package es.jvbabi.overmail.data.remote

import es.jvbabi.overmail.BuildKonfig
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

/**
 * Everything the app asks the server for. The routes all live under `/api`, which is what Caddy
 * forwards to Ktor -- see deploy/Caddyfile.
 */
class OvermailApi(
    private val httpClient: HttpClient,
) {
    private val baseUrl = BuildKonfig.SERVER_URL.trimEnd('/')

    /** Whether the server answers at all. The one route that needs no session. */
    suspend fun isHealthy(): Boolean = runCatching {
        val response = httpClient.get("$baseUrl/api/health")
        response.status.isSuccess() && response.bodyAsText() == "ok"
    }.getOrDefault(false)
}
