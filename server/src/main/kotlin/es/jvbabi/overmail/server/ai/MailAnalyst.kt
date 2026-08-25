package es.jvbabi.overmail.server.ai

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.model.PromptExecutorBuilder
import ai.koog.prompt.executor.model.executeStructured
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import es.jvbabi.overmail.server.config.AiConfig

/**
 * What one step came back with, and what it cost.
 *
 * A step that could not be answered is not an exception here: a mail whose sender nobody can name
 * is a normal outcome of a mailbox full of machine mail, and a backend that is not running is not
 * the reader's problem either. Both read as [failure] with a line saying which it was.
 */
data class AnalysisResult<T>(
    val value: T?,
    val usage: TokenUsage?,
    /** Why there is no value, or null when there is one. */
    val failure: String? = null,
)

/**
 * Runs the analysis steps against one mail.
 *
 * One of these per server: it holds the connection to the model backend, which is a pool and not
 * something to build per mail. Which model a step gets comes from its tier -- the fast one for
 * reading facts out of a mail, the configured main one for anything that has to weigh something.
 *
 * A step whose answer does not pass its own [MailAnalysisStep.validate] is asked once more with
 * that sentence attached. Once, not until it works: a model that answers the same nonsense twice
 * will answer it a third time too.
 */
class MailAnalyst(private val config: AiConfig) {

    private val provider = when (config.type.lowercase()) {
        "ollama" -> LLMProvider.Ollama
        // Everything OpenAI-compatible: LM Studio, vLLM, OpenAI itself, see AiConfig.
        else -> LLMProvider.OpenAI
    }

    private val executor: PromptExecutor = PromptExecutorBuilder()
        .addClient(
            when (provider) {
                LLMProvider.Ollama -> OllamaClient(baseUrl = config.host)
                else -> OpenAILLMClient(
                    apiKey = config.apiKey.orEmpty(),
                    settings = OpenAIClientSettings(baseUrl = config.host),
                )
            }
        )
        .build()

    /** Runs [step] against [context], or says why it could not. */
    suspend fun <T> run(step: MailAnalysisStep<T>, context: MailContext): AnalysisResult<T> {
        val first = ask(step, context, complaint = null)

        // Nothing to fix, or nothing to fix it with: a backend that failed is not a wrong answer.
        val complaint = first.value?.let(step.validate) ?: return first

        val second = ask(step, context, complaint)

        return when {
            second.value == null -> second.copy(usage = first.usage?.plus(second.usage ?: NOTHING))
            step.validate(second.value) != null -> AnalysisResult(
                value = null,
                usage = first.usage?.plus(second.usage ?: NOTHING),
                failure = "The model answered twice with the same problem: $complaint",
            )
            else -> second.copy(usage = first.usage?.plus(second.usage ?: NOTHING))
        }
    }

    private suspend fun <T> ask(
        step: MailAnalysisStep<T>,
        context: MailContext,
        complaint: String?,
    ): AnalysisResult<T> {
        val model = LLModel(
            provider = provider,
            id = modelFor(step.tier),
            // JSON schema and nothing else: these steps fill a shape, they do not call tools.
            capabilities = listOf(LLMCapability.Schema.JSON.Standard, LLMCapability.Completion),
        )

        val request = prompt(
            id = step.id,
            // Zero temperature because the point is that two mails of the same kind get the same
            // answer, see the shared rules.
            params = LLMParams(temperature = 0.0, maxTokens = step.maxOutputTokens),
        ) {
            system(step.systemPrompt)
            user(context.asMessage())
            // The second ask carries what was wrong with the first, so the model has something to
            // go on other than the same prompt again.
            if (complaint != null) user("Your last answer was not usable: $complaint")
        }

        val response = executor.executeStructured(request, model, step.serializer)

        return response.fold(
            onSuccess = { structured ->
                AnalysisResult(
                    value = structured.data,
                    usage = TokenUsage(
                        input = structured.message.metaInfo.inputTokensCount,
                        output = structured.message.metaInfo.outputTokensCount,
                        // Ollama reports no separate count for thinking, and these steps ask for
                        // none: `/no_think` is the first line of every prompt.
                        reasoningCharacters = null,
                    ),
                )
            },
            onFailure = { cause ->
                AnalysisResult(
                    value = null,
                    usage = null,
                    failure = cause.message ?: cause::class.simpleName ?: "the model did not answer",
                )
            },
        )
    }

    /** The model a tier resolves to. Falls back to the main one when the config names no fast one. */
    private fun modelFor(tier: ModelTier): String = when (tier) {
        ModelTier.FAST -> config.fastModel ?: config.model
        ModelTier.CAPABLE -> config.model
    }
}

/** Nothing counted, for adding up two runs where one backend reported no numbers. */
private val NOTHING = TokenUsage(input = null, output = null, reasoningCharacters = null)
