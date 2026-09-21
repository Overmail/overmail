package es.jvbabi.overmail.server.http.api

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The app believes a status only when this header is on it, so it has to be on every answer --
 * the failing ones most of all, since those are the ones the app branches on.
 */
class BackendHeadersTest {

    @Test
    fun `every answer says it comes from the server, failures included`() = testApplication {
        application {
            installBackendHeaders()
            installApiErrorHandling()
            routing {
                route("/api") {
                    get("/ok") { call.respondText("ok") }
                    get("/broken") { error("a bug") }
                }
            }
        }

        val answers = mapOf(
            "/api/ok" to HttpStatusCode.OK,
            "/api/nothing-here" to HttpStatusCode.NotFound,
            "/api/broken" to HttpStatusCode.InternalServerError,
        )

        answers.forEach { (path, status) ->
            val response = client.get(path)
            assertEquals(status, response.status, path)
            assertEquals(BACKEND_FAMILY, response.headers[BACKEND_FAMILY_HEADER], path)
        }
    }
}
