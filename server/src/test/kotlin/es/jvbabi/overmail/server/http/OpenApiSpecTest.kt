package es.jvbabi.overmail.server.http

import es.jvbabi.overmail.server.auth.overmailSession
import es.jvbabi.overmail.server.auth.registerSessionSecurityScheme
import es.jvbabi.overmail.server.http.api.installApiErrorHandling
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The spec is assembled from KDoc on the routes with code inference switched off (see the `ktor`
 * block in `server/build.gradle.kts`), so a route nobody documented shows up with nothing but its
 * path. This is what catches that, rather than a reader of `/api/swagger` finding it.
 */
class OpenApiSpecTest {

    private val methods = setOf("get", "put", "post", "delete", "patch", "head", "options", "trace")

    @Test
    fun `every operation is documented`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            installApiErrorHandling()
            install(Authentication) { overmailSession() }
            registerSessionSecurityScheme()
            install(SSE)
            install(WebSockets)
            configureRouting()
        }

        val response = client.get("/api/swagger/documentation.json")
        assertEquals(HttpStatusCode.OK, response.status)
        val spec = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        val declaredTags = spec.getValue("tags").jsonArray.map { it.jsonObject.string("name") }.toSet()
        val schemas = spec.getValue("components").jsonObject["schemas"]?.jsonObject?.keys.orEmpty()
        val securitySchemes = spec.getValue("components").jsonObject["securitySchemes"]?.jsonObject?.keys.orEmpty()

        val problems = mutableListOf<String>()
        val operations = spec.getValue("paths").jsonObject.flatMap { (path, item) ->
            item.jsonObject.filterKeys { it in methods }.map { (method, operation) ->
                "${method.uppercase()} $path" to operation.jsonObject
            }
        }
        assertTrue(operations.isNotEmpty(), "the spec lists no operations at all")

        for ((name, operation) in operations) {
            val summary = operation["summary"]?.jsonPrimitive?.content.orEmpty()
            if (summary.isBlank()) problems += "$name has no summary"
            if ('\n' in summary) problems += "$name has a summary over several lines: prose above a Key: line"

            val tags = operation["tags"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            if (tags.size != 1) problems += "$name has ${tags.size} tags instead of one: $tags"
            tags.filterNot { it in declaredTags }.forEach { problems += "$name uses the undeclared tag $it" }

            val responses = operation["responses"]?.jsonObject.orEmpty()
            if (responses.keys.none { it.startsWith("2") }) problems += "$name documents no success response"
            responses.forEach { (status, body) ->
                if (body.jsonObject["description"]?.jsonPrimitive?.content.isNullOrBlank()) {
                    problems += "$name does not describe its $status"
                }
            }

            operation["security"]?.jsonArray
                ?.flatMap { it.jsonObject.keys }
                ?.filterNot { it in securitySchemes }
                ?.forEach { problems += "$name needs the undefined security scheme $it" }

            references(operation)
                .map { it.removePrefix("#/components/schemas/") }
                .filterNot { it in schemas }
                .forEach { problems += "$name refers to the missing schema $it" }
        }

        assertTrue(problems.isEmpty(), problems.joinToString(separator = "\n", prefix = "\n"))
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun references(element: JsonElement): List<String> = when (element) {
        is JsonObject -> element.flatMap { (key, value) ->
            if (key == "\$ref") listOf(value.jsonPrimitive.content) else references(value)
        }
        is JsonArray -> element.flatMap(::references)
        else -> emptyList()
    }
}
