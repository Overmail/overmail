package es.jvbabi.overmail.server.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The `ai` section of `data/config.json`. */
@Serializable
data class AiConfig(
    /**
     * Which API [host] speaks: `openai` for anything OpenAI-compatible (LM Studio, vLLM, OpenAI
     * itself), or `ollama` for Ollama's own API.
     */
    @SerialName("type") val type: String,

    /** Base URL of the backend, without the API path: `http://127.0.0.1:1234`. */
    @SerialName("host") val host: String,

    /** Model tag as the backend knows it, e.g. `qwen3:30b-a3b`. */
    @SerialName("model") val model: String,

    /**
     * Model for the analysis steps that only read facts out of a mail, see
     * [es.jvbabi.overmail.server.ai.ModelTier]. Falls back to [model] when the config names none.
     */
    @SerialName("fast_model") val fastModel: String? = null,

    /** Only for `openai`, and only where the endpoint checks it -- LM Studio does not. */
    @SerialName("api_key") val apiKey: String? = null,
)
