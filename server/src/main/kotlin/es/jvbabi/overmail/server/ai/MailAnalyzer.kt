package es.jvbabi.overmail.server.ai

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.functionalStrategy
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.executor.ollama.client.OllamaParams
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.params.LLMParams
import es.jvbabi.overmail.server.config.AiConfig
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private const val OPENAI = "openai"
private const val OLLAMA = "ollama"

/** What one step answered, and what the run cost. */
data class StepResult<T>(
    val value: T,
    val usage: TokenUsage,
)

/**
 * Runs the analysis steps: owns the connection to the models and turns a [MailAnalysisStep] into
 * the agent that carries it out.
 *
 * The steps themselves stay data ([MailOriginStep] and whatever follows it) -- everything about
 * talking to a model lives here, so a new step is a prompt and a schema, not another piece of
 * wiring. It is also why the object graph hands out this one type instead of a finished
 * `AIAgent<X, Y>` per step: a key per step would not survive two steps sharing a signature.
 */
class MailAnalyzer(config: AiConfig) {

    private val executor: PromptExecutor
    private val models: Map<ModelTier, LLModel>
    private val params: LLMParams

    init {
        val backend = backendFor(config)

        executor = MultiLLMPromptExecutor(mapOf(backend.provider to backend.client))
        params = backend.params
        models = mapOf(
            // Both tiers land on the same model until the config names a second one: the tier is
            // what a step asks for, not a promise that two models are configured.
            ModelTier.FAST to backend.model(config.fastModel ?: config.model),
            ModelTier.CAPABLE to backend.model(config.model),
        )
    }

    /**
     * Puts [context] through [step]. The agent is built per call: it is a prompt, a schema and a
     * strategy, and holds no connection of its own -- that sits in [executor] and is shared.
     */
    suspend fun <T> run(step: MailAnalysisStep<T>, context: MailContext): StepResult<T> =
        agentFor(step).run(context).also { result ->
            // Thinking is switched off twice over, in the request and in the prompt. If a model
            // thinks anyway it costs the step its latency, and nothing about the answer would show
            // it -- so say so rather than let it pass.
            result.usage.reasoningCharacters?.let { characters ->
                System.err.println("[${step.id}] model thought for $characters characters despite thinking being off")
            }
        }

    private fun <T> agentFor(step: MailAnalysisStep<T>): AIAgent<MailContext, StepResult<T>> = AIAgent(
        promptExecutor = executor,
        agentConfig = AIAgentConfig(
            prompt = prompt(step.id, params = params) { system(step.systemPrompt) },
            model = models.getValue(step.tier),
            // One request per mail, no tools to come back from.
            maxAgentIterations = 1,
        ),
        strategy = functionalStrategy(step.id) { context ->
            llm.writeSession {
                appendPrompt { user(context.asMessage()) }

                val response = requestLLMStructured(step.serializer).getOrThrow()
                StepResult(response.data, response.message.usage())
            }
        },
    )

}

/**
 * A backend and what its API is able to do. Kept together because the three belong together: an
 * OpenAI-compatible endpoint has to be addressed as one, and a model is only usable through the
 * client that was built for its provider.
 */
private class Backend(
    val client: LLMClient,
    val provider: LLMProvider,
    private val capabilities: List<LLMCapability>,

    /**
     * How every request is sent. Temperature 0, because two mails of the same kind must not come
     * back classified differently just because the model sampled another token -- and thinking
     * switched off through whatever knob this backend offers for it, see [backendFor].
     */
    val params: LLMParams,
) {
    /**
     * A model is described by its tag alone: unlike the models Koog ships constants for, whatever
     * a self-hosted backend serves is not known here, so the capabilities are what the steps rely
     * on -- `Schema.JSON` is what lets Koog ask for an answer in the shape of a data class instead
     * of parsing prose back out.
     */
    fun model(tag: String): LLModel = LLModel(provider = provider, id = tag, capabilities = capabilities)
}

private fun backendFor(config: AiConfig): Backend {
    val shared = listOf(
        LLMCapability.Completion,
        LLMCapability.Temperature,
        LLMCapability.Schema.JSON.Standard,
    )

    return when (config.type) {
        OPENAI -> Backend(
            // Thinking off at the request, not just in the prompt. `reasoning_effort` is the one
            // LM Studio acts on: without it a thinking model answers into the reasoning channel
            // and leaves the content empty, which is no answer at all as far as the structured
            // parser is concerned. `chat_template_kwargs` does nothing there but is the switch
            // llama.cpp and vLLM read, so both go out.
            params = OpenAIChatParams(
                temperature = 0.0,
                reasoningEffort = ReasoningEffort.NONE,
                additionalProperties = mapOf(
                    "chat_template_kwargs" to buildJsonObject { put("enable_thinking", JsonPrimitive(false)) },
                ),
            ),
            client = OpenAILLMClient(
                // LM Studio ignores the key, a hosted endpoint will not: it comes from the config
                // when there is one.
                apiKey = config.apiKey.orEmpty(),
                settings = OpenAIClientSettings(baseUrl = config.host),
                httpClientFactory = KtorKoogHttpClient.Factory(),
            ),
            provider = LLMProvider.OpenAI,
            capabilities = shared +
                // Chat Completions, explicitly: without this Koog reaches for OpenAI's Responses
                // API, which a local server like LM Studio does not serve.
                LLMCapability.OpenAIEndpoint.Completions +
                // Declared so it can be switched off: Koog drops `reasoning_effort` from the
                // request unless the model claims to think. Without the claim the parameter never
                // reaches the server, the model thinks, and its whole answer arrives in the
                // reasoning channel with an empty content -- which is no answer at all to the
                // structured parser.
                LLMCapability.Thinking,
        )

        OLLAMA -> Backend(
            // Ollama has the switch as a field of its own.
            params = OllamaParams(temperature = 0.0, think = false),
            client = OllamaClient(
                httpClientFactory = KtorKoogHttpClient.Factory(),
                baseUrl = config.host,
            ),
            provider = LLMProvider.Ollama,
            capabilities = shared,
        )

        else -> error("Unsupported ai.type '${config.type}', expected '$OPENAI' or '$OLLAMA'")
    }
}

/**
 * The counts come off the answering message: Ollama reports what it read and wrote per request,
 * so this is measured rather than estimated from the text.
 */
private fun Message.Assistant.usage(): TokenUsage = TokenUsage(
    input = metaInfo.inputTokensCount,
    output = metaInfo.outputTokensCount,
    // Reasoning arrives as its own message part, one entry per block.
    reasoningCharacters = parts
        .filterIsInstance<MessagePart.Reasoning>()
        .sumOf { part -> part.content.sumOf { it.length } }
        .takeIf { it > 0 },
)
