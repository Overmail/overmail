package es.jvbabi.overmail.server.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The `ai` section of `data/config.json`. */
@Serializable
data class AiConfig(
    /**
     * Which API [baseUrl] speaks: `openai` for anything OpenAI-compatible (LM Studio, vLLM, OpenAI
     * itself), or `ollama` for Ollama's own API.
     */
    @SerialName("type") val type: String,

    /**
     * Base URL of the backend, without the API path: `http://127.0.0.1:1234`.
     *
     * Written as the config names it, which is not what the clients want -- read [apiBaseUrl].
     */
    @SerialName("base_url") val baseUrl: String,

    /** Model tag as the backend knows it, e.g. `qwen3:30b-a3b`. */
    @SerialName("model") val model: String,

    /**
     * Model for the analysis steps that only read facts out of a mail, see
     * [es.jvbabi.overmail.server.ai.ModelTier]. Falls back to [model] when the config names none.
     */
    @SerialName("fast_model") val fastModel: String? = null,

    /** Only for `openai`, and only where the endpoint checks it -- LM Studio does not. */
    @SerialName("api_key") val apiKey: String? = null,
) {

    /**
     * [baseUrl] as the model clients want it: the host, and nothing of the API path.
     *
     * Both clients append their own path -- `v1/chat/completions` for the OpenAI one, `api/...` for
     * Ollama -- to a base URL they first give a trailing slash, so anything already ending in `/v1`
     * comes out as `/v1/v1/chat/completions` and the endpoint answers 404. Hosted providers publish
     * their URL with the `/v1` on it and it is what one pastes into the config, so it is dropped
     * here rather than left as a trap.
     */
    val apiBaseUrl: String get() = baseUrl.trimEnd('/').removeSuffix("/v1")
}
