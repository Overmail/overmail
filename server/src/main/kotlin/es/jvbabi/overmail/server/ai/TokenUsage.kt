package es.jvbabi.overmail.server.ai

/** What one agent run cost, as the model reported it. Null where the backend does not say. */
data class TokenUsage(
    val input: Int?,

    /** Everything the model wrote, thinking included: Ollama counts it in one number. */
    val output: Int?,

    /**
     * Length of the thinking the model returned. In characters, not tokens -- Ollama reports no
     * separate count for it, only the text.
     */
    val reasoningCharacters: Int?,
)
