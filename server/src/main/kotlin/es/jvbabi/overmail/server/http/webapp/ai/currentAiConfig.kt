package es.jvbabi.overmail.server.http.webapp.ai

import es.jvbabi.overmail.server.config.ApplicationConfig
import io.ktor.server.auth.*
import io.ktor.server.plugins.di.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

fun Route.currentAiConfig() {
    authenticate {
        get {
            val config = application.dependencies.resolve<ApplicationConfig>()
            call.respond(CurrentAiConfigResponse(modelId = config.ai.model))
        }
    }
}

@Serializable
private data class CurrentAiConfigResponse(
    @SerialName("model_id") val modelId: String
)