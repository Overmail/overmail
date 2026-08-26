package es.jvbabi.overmail.server.ai

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.model.PromptExecutorBuilder
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
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

    /**
     * What the backend has to be able to do for these steps.
     *
     * The OpenAI client reads the request shape off this list, and a model it does not know by name
     * -- anything a local LM Studio or vLLM serves -- leaves it with nothing to go on: it refuses
     * with "cannot determine proper LLM params" rather than guess. So the endpoint is named here,
     * and it is the chat completions one, which is what the local servers speak.
     */
    private val capabilities = buildList {
        add(LLMCapability.Completion)
        add(LLMCapability.Temperature)
        // JSON schema and nothing else: these steps fill a shape, they do not call tools.
        add(LLMCapability.Schema.JSON.Standard)
        if (provider != LLMProvider.Ollama) add(LLMCapability.OpenAIEndpoint.Completions)
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

    /**
     * Runs [step] against [context], or says why it could not.
     *
     * Everything the step sends and everything that comes back goes to [log] as it happens, in
     * order: the prompts verbatim, the raw answer before anything parsed it, and whatever went
     * wrong instead. A caller that only wants the answer leaves it out.
     */
    suspend fun <T> run(
        step: MailAnalysisStep<T>,
        context: MailContext,
        log: AgentLog = {},
    ): AnalysisResult<T> {
        val first = ask(step, context, complaint = null, attempt = 1, log = log)

        // Nothing to fix, or nothing to fix it with: a backend that failed is not a wrong answer.
        val complaint = first.value?.let(step.validate) ?: return first

        val second = ask(step, context, complaint, attempt = 2, log = log)
        val spent = first.usage?.plus(second.usage ?: NOTHING)

        return when {
            second.value == null -> second.copy(usage = spent)
            step.validate(second.value) != null -> {
                val failure = "The model answered twice with the same problem: $complaint"
                log(AgentLine(step.id, attempt = 2, role = AgentRole.ERROR, text = failure))

                AnalysisResult(value = null, usage = spent, failure = failure)
            }
            else -> second.copy(usage = spent)
        }
    }

    private suspend fun <T> ask(
        step: MailAnalysisStep<T>,
        context: MailContext,
        complaint: String?,
        attempt: Int,
        log: AgentLog,
    ): AnalysisResult<T> {
        val model = LLModel(
            provider = provider,
            id = modelFor(step.tier),
            capabilities = capabilities,
        )

        // Built before the request rather than inside it, because they are logged as well as
        // sent, and a log of what was nearly sent is worth nothing.
        val mail = context.asMessage()
        // The second ask carries what was wrong with the first, so the model has something to go
        // on other than the same prompt again.
        val objection = complaint?.let { "Your last answer was not usable: $it" }

        val request = prompt(
            id = step.id,
            // Zero temperature because the point is that two mails of the same kind get the same
            // answer, see the shared rules.
            params = LLMParams(
                temperature = 0.0,
                maxTokens = step.maxOutputTokens,
                // The schema goes in the params, which is what both backends turn into their own
                // structured-output field. Koog's `executeStructured` would do this too, and then
                // parse the answer itself -- which is the part that cannot be used here, see
                // [answerText].
                schema = step.structure.schema,
            ),
        ) {
            system(step.systemPrompt)
            user(mail)
            if (objection != null) user(objection)
        }

        // Logged before the request goes out, not after it comes back: a backend that hangs is the
        // case where the prompt is most worth reading, and by then it would never have been sent.
        log(AgentLine(step.id, attempt, AgentRole.SYSTEM, step.systemPrompt))
        log(AgentLine(step.id, attempt, AgentRole.USER, mail))
        if (objection != null) log(AgentLine(step.id, attempt, AgentRole.USER, objection))

        val answer = try {
            executor.execute(request, model)
        } catch (cause: Exception) {
            val failure = cause.message ?: cause::class.simpleName ?: "the model did not answer"
            log(AgentLine(step.id, attempt, AgentRole.ERROR, failure))

            return AnalysisResult(value = null, usage = null, failure = failure)
        }

        val thinking = answer.thinkingText()
        val spoken = answer.textContent().takeIf { it.isNotBlank() }

        val usage = TokenUsage(
            input = answer.metaInfo.inputTokensCount,
            output = answer.metaInfo.outputTokensCount,
            // In characters, because no backend reports a token count for it separately.
            reasoningCharacters = thinking?.length,
        )

        // Both channels, each as its own line, and the text as it came rather than as it parsed: a
        // model that fills the schema with the wrong thing looks fine once parsed. The cost goes on
        // whichever line is the last of the answer.
        if (thinking != null) {
            log(AgentLine(step.id, attempt, AgentRole.THINKING, thinking, usage.takeIf { spoken == null }))
        }
        if (spoken != null) log(AgentLine(step.id, attempt, AgentRole.ASSISTANT, spoken, usage))

        val text = answerText(spoken, thinking)
            ?: return failed(step, attempt, usage, "The model answered with nothing at all.", log)

        return try {
            AnalysisResult(value = step.structure.parse(text), usage = usage)
        } catch (cause: Exception) {
            val why = cause.message ?: cause::class.simpleName ?: "it could not be read"
            failed(step, attempt, usage, "The answer was not the shape that was asked for: $why", log)
        }
    }

    /** An ask that got somewhere but not to an answer. The cost stands either way. */
    private suspend fun <T> failed(
        step: MailAnalysisStep<T>,
        attempt: Int,
        usage: TokenUsage,
        failure: String,
        log: AgentLog,
    ): AnalysisResult<T> {
        log(AgentLine(step.id, attempt, AgentRole.ERROR, failure))

        return AnalysisResult(value = null, usage = usage, failure = failure)
    }

    /** The model a tier resolves to. Falls back to the main one when the config names no fast one. */
    private fun modelFor(tier: ModelTier): String = when (tier) {
        ModelTier.FAST -> config.fastModel ?: config.model
        ModelTier.CAPABLE -> config.model
    }
}

/**
 * The text an answer is parsed out of: what the model said, or failing that what it thought.
 *
 * The fallback is not a nicety. A thinking model behind LM Studio hands its whole completion back
 * as `reasoning_content` and leaves `content` empty, so a perfectly good answer -- schema filled,
 * `finish_reason` "stop", seventeen tokens -- arrives with nothing in the channel anyone looks at.
 * Koog's own structured parse takes the text parts and calls `single()` on them, which on such a
 * response fails with "List is empty." and throws the answer away with it. Reading the thinking
 * when there is nothing else is what makes those models usable at all.
 *
 * Order matters: what a model says beats what it thought on its way there, so the thinking is only
 * ever read when the model said nothing.
 */
private fun answerText(spoken: String?, thinking: String?): String? = spoken ?: thinking

/** What the model reported as its thinking, or null where it reported none. */
private fun Message.Assistant.thinkingText(): String? = parts
    .filterIsInstance<MessagePart.Reasoning>()
    .flatMap { it.content }
    .joinToString("\n")
    .takeIf { it.isNotBlank() }

/** Nothing counted, for adding up two runs where one backend reported no numbers. */
private val NOTHING = TokenUsage(input = null, output = null, reasoningCharacters = null)
