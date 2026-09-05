package es.jvbabi.overmail.server.http.webapp.ai

import es.jvbabi.overmail.server.config.ApplicationConfig
import es.jvbabi.overmail.server.http.api.dependency
import io.ktor.server.auth.authenticate
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

fun Route.currentAiConfig() {
    authenticate {
        get {
            val config = call.dependency<ApplicationConfig>()
            call.respond(CurrentAiConfigResponse(modelId = config.ai.model))
        }
    }
}

@Serializable
private data class CurrentAiConfigResponse(
    @SerialName("model_id") val modelId: String
)