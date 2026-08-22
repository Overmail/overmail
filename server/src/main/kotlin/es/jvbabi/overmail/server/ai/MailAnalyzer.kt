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
import ai.koog.prompt.executor.model.StructureFixingParser
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.executor.ollama.client.OllamaParams
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.structure.LLMStructuredParsingError
import es.jvbabi.overmail.server.config.AiConfig
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private const val OPENAI = "openai"
private const val OLLAMA = "ollama"

/**
 * How often a malformed answer is handed back to be written again as valid JSON.
 *
 * Small models get the shape right and the syntax wrong: a non-breaking space where the quotation
 * mark belongs, a smart quote around a German word. The answer is there, it just cannot be read,
 * and asking for the same text again is far cheaper than asking for the whole analysis again.
 */
private const val STRUCTURE_FIX_RETRIES = 2

/**
 * What is said to the model when its answer could not be read. Not a description of the JSON
 * error: the model that wrote the broken text has already been asked to repair it and could not,
 * so this asks for the answer afresh rather than for the same text again.
 */
private const val UNREADABLE_COMPLAINT =
    "It was not valid JSON and could not be read at all. Answer with the requested structure as " +
        "plain JSON, using ordinary ASCII quotation marks and spaces and nothing around it."

/**
 * The model answered, and the answer could not be read. Its own kind of failure: unlike a model
 * that is unreachable, this is worth asking about again straight away, and unlike an answer that is
 * merely incomplete there is nothing in it to keep.
 */
private class UnreadableAnswerException(cause: Throwable) : Exception(cause.message, cause)

/**
 * Whether a failure is the answer's fault rather than the connection's. Koog wraps what the JSON
 * parser threw, and the wrapping differs by how the request was made, so the chain is walked
 * instead of matching on the top exception.
 */
private fun Throwable.isUnreadableAnswer(): Boolean {
    var cause: Throwable? = this
    // Bounded, so a cause that points at itself cannot spin here.
    repeat(10) {
        when (cause) {
            null -> return false
            is LLMStructuredParsingError, is SerializationException -> return true
            else -> cause = cause?.cause
        }
    }
    return false
}

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

    /**
     * Rewrites an answer that came back as broken JSON. Always the fast model: this is a
     * transcription job, not an analysis -- the content is already there.
     */
    private val fixingParser: StructureFixingParser

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
        fixingParser = StructureFixingParser(
            model = models.getValue(ModelTier.FAST),
            retries = STRUCTURE_FIX_RETRIES,
        )
    }

    /**
     * Puts [context] through [step]. The agent is built per call: it is a prompt, a schema and a
     * strategy, and holds no connection of its own -- that sits in [executor] and is shared.
     *
     * [material] is for a step that needs more than the mail itself, such as the neighbouring
     * mails a tagging is reviewed against. It goes to the model as a second message, after the
     * mail, so a step that needs nothing extra reads exactly as it did before.
     *
     * An answer the step itself rejects buys one more request, and the last one is what comes
     * back -- a second miss is logged rather than thrown, since a mail filed with a gap in it is
     * still better than a mail the queue never gets past.
     */
    suspend fun <T> run(
        step: MailAnalysisStep<T>,
        context: MailContext,
        material: String? = null,
    ): StepResult<T> {
        val first = try {
            attempt(step, context, material)
        } catch (unreadable: UnreadableAnswerException) {
            // The repairs inside the request are already spent at this point, so what is left is
            // to ask for the answer again from the top. Rethrown if that fails too: the mail then
            // stays unstamped and comes round again, which is better than filing it half done.
            System.err.println("[${step.id}] unreadable answer: ${unreadable.message} -- asking once more")
            val retried = attempt(step, context, material, UNREADABLE_COMPLAINT)
            step.validate(retried.value)?.let { complaint ->
                System.err.println("[${step.id}] readable but unusable: $complaint -- taking it as it is")
            }
            return retried
        }

        // What the schema cannot ask for -- a reason that is there because another field is filled
        // -- is asked for here, and an answer that misses it is worth one more request: the step
        // is cheap next to the mail arriving filed without it.
        val complaint = step.validate(first.value) ?: return first

        System.err.println("[${step.id}] unusable answer: $complaint -- asking once more")
        val second = try {
            attempt(step, context, material, complaint)
        } catch (unreadable: UnreadableAnswerException) {
            // The first answer parsed and was merely imperfect, so it is worth more than nothing:
            // dropping it here would cost the mail its filing over the retry, not over itself.
            System.err.println("[${step.id}] retry came back unreadable: ${unreadable.message} -- keeping the first answer")
            return first
        }

        step.validate(second.value)?.let { again ->
            System.err.println("[${step.id}] still unusable: $again -- taking it as it is")
        }

        // Both requests were spent on this step, so both are what it cost.
        return StepResult(second.value, first.usage + second.usage)
    }

    /**
     * One request. [complaint] is what was wrong with the answer before it, if there was one.
     *
     * @throws UnreadableAnswerException when the model answered but its JSON could not be read,
     *   even after the repairs. Anything else -- a model that is down, a request that timed out --
     *   comes out as it is: those are not answered by asking again straight away.
     */
    private suspend fun <T> attempt(
        step: MailAnalysisStep<T>,
        context: MailContext,
        material: String?,
        complaint: String? = null,
    ): StepResult<T> =
        runCatching { agentFor(step, material, complaint).run(context) }
            .getOrElse { cause ->
                if (cause.isUnreadableAnswer()) throw UnreadableAnswerException(cause)
                throw cause
            }
            .also { result ->
                // Thinking is switched off twice over, in the request and in the prompt. If a model
                // thinks anyway it costs the step its latency, and nothing about the answer would
                // show it -- so say so rather than let it pass.
                result.usage.reasoningCharacters?.let { characters ->
                    System.err.println("[${step.id}] model thought for $characters characters despite thinking being off")
                }
            }

    private fun <T> agentFor(
        step: MailAnalysisStep<T>,
        material: String?,
        complaint: String?,
    ): AIAgent<MailContext, StepResult<T>> = AIAgent(
        promptExecutor = executor,
        agentConfig = AIAgentConfig(
            // The backend's params carry temperature and the thinking switch; the step adds what
            // its own answer is allowed to cost.
            prompt = prompt(step.id, params = params.copy(maxTokens = step.maxOutputTokens)) {
                system(step.systemPrompt)
            },
            model = models.getValue(step.tier),
            // One request per mail, no tools to come back from.
            maxAgentIterations = 1,
        ),
        strategy = functionalStrategy(step.id) { context ->
            llm.writeSession {
                appendPrompt {
                    user(context.asMessage())
                    material?.let { user(it) }
                    // The attempt that failed is not in this conversation -- every request is a
                    // fresh agent -- so what was wrong with it is stated rather than pointed at.
                    complaint?.let {
                        user("Your last answer to this was not usable: $it Answer again, in the same structure.")
                    }
                }

                // The fixing parser is what stands between a small model's punctuation and a mail
                // that never gets filed, see STRUCTURE_FIX_RETRIES.
                val response = requestLLMStructured(step.serializer, fixingParser = fixingParser)
                    .getOrThrow()
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
