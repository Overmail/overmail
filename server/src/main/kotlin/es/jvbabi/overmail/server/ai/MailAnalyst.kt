package es.jvbabi.overmail.server.ai

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
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
import kotlinx.serialization.json.JsonObject
import es.jvbabi.overmail.server.config.AiConfig
import kotlinx.coroutines.runBlocking

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
 * A step whose answer is not usable is asked again with the reason attached, up to [MAX_ATTEMPTS]
 * times in all. Two different problems get the same treatment, because both are the model's and
 * both are fixable by telling it so: an answer that filled the shape with the wrong thing, and an
 * answer that carried no object at all -- a model that wrote a paragraph where JSON was asked for
 * is told to answer with the JSON alone, and usually does.
 *
 * What is not retried is a backend that did not answer. There is nobody to complain to about a
 * connection that was refused, and asking six times over is six times as long to find that out.
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

    /**
     * The same for the step that works by calling tools, see [converse].
     *
     * Its own list rather than one list with everything on it. This is what the client turns into
     * the shape of the request, and a backend told the model can do both gets a schema and a tool
     * list in one call -- which the local servers answer in whichever way they feel like. Each kind
     * of step asks for exactly what it uses, and the steps that were working keep working.
     */
    private val toolCapabilities = buildList {
        add(LLMCapability.Completion)
        add(LLMCapability.Temperature)
        add(LLMCapability.Tools)
        if (provider != LLMProvider.Ollama) add(LLMCapability.OpenAIEndpoint.Completions)
    }

    private val executor: PromptExecutor = PromptExecutorBuilder()
        .addClient(
            when (provider) {
                LLMProvider.Ollama -> OllamaClient(
                    baseUrl = config.apiBaseUrl,
                    timeoutConfig = TIMEOUTS,
                )
                else -> OpenAILLMClient(
                    apiKey = config.apiKey.orEmpty(),
                    settings = OpenAIClientSettings(
                        baseUrl = config.apiBaseUrl,
                        timeoutConfig = TIMEOUTS,
                    ),
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
        var spent: TokenUsage? = null
        // What was wrong with the answer before this one, and therefore what the next ask carries.
        var complaint: String? = null

        for (attempt in 1..MAX_ATTEMPTS) {
            val ask = ask(step, context, complaint, attempt, log)
            // Every attempt is paid for, the ones that came back unusable included.
            spent = spent?.plus(ask.result.usage ?: NOTHING) ?: ask.result.usage

            val answer = ask.result.value

            if (answer == null) {
                // Nothing to fix it with: the model never spoke, so there is nothing to tell it.
                if (!ask.answered) return ask.result.copy(usage = spent)

                // It spoke and what it said could not be read -- no JSON in it, or JSON that was
                // not this shape. That is the model's to fix and it is told so on the next ask.
                complaint = ask.result.failure ?: NO_JSON
                continue
            }

            // Usable, and the last word on the step: what the model says the mail is.
            complaint = step.validate(answer, context) ?: return ask.result.copy(usage = spent)
        }

        val failure = "The model gave no usable answer in $MAX_ATTEMPTS tries. The last problem " +
            "was: $complaint"
        log(AgentLine(step.id, attempt = MAX_ATTEMPTS, role = AgentRole.ERROR, text = failure))

        return AnalysisResult(value = null, usage = spent, failure = failure)
    }

    /**
     * One request, and whether the model answered it at all.
     *
     * The two are told apart because they are retried differently: an answer nobody can read is the
     * model's problem and worth another ask, a backend that never answered is not, see [run].
     */
    private class Attempt<T>(val result: AnalysisResult<T>, val answered: Boolean)

    /**
     * Runs [step] as a conversation: the model calls tools, [run] carries them out, and the answers
     * go back to it until it says it is done or [MailToolStep.maxRounds] is up.
     *
     * Everything goes to [log] as it happens, as in [run]: the prompts, the calls with the arguments
     * the model wrote, and every answer a tool gave. A conversation is the one kind of step where
     * that log is not a nicety -- it is the only record of why the mailbox looks different than it
     * did, and it is written as the changes are made rather than after.
     *
     * The tools change things as they are called. So a conversation that is cut off, or one whose
     * backend drops halfway, leaves behind whatever it had already done -- and that is the intended
     * behaviour rather than a hole: every one of those changes was one the model asked for, each of
     * them says why it is there, and none of them is worth rolling back because the model went on to
     * lose its train of thought. What comes back says where it stopped, see [ConversationResult].
     */
    suspend fun converse(
        step: MailToolStep,
        context: MailContext,
        briefing: String,
        run: ToolRunner,
        log: AgentLog = {},
    ): ConversationResult {
        val model = LLModel(
            provider = provider,
            id = modelFor(step.tier),
            capabilities = toolCapabilities,
        )

        val mail = context.asMessage()

        log(AgentLine(step.id, attempt = 1, role = AgentRole.SYSTEM, text = step.systemPrompt))
        log(AgentLine(step.id, attempt = 1, role = AgentRole.USER, text = mail))
        log(AgentLine(step.id, attempt = 1, role = AgentRole.USER, text = briefing))

        var spent: TokenUsage? = null
        // The conversation so far, replayed into every request: what the model answered and what
        // the tools answered it, in the order it happened. Nothing here is ever dropped -- a model
        // that cannot see the mail it looked at two rounds ago looks at it again.
        val turns = mutableListOf<Turn>()

        for (round in 1..step.maxRounds) {
            val request = prompt(
                id = step.id,
                params = LLMParams(temperature = 0.0, maxTokens = step.maxOutputTokens),
            ) {
                system(step.systemPrompt)
                user(mail)
                user(briefing)

                for (turn in turns) when (turn) {
                    is Turn.Said -> message(turn.answer)
                    is Turn.Answered -> toolResult(
                        tool = turn.tool,
                        output = turn.text,
                        id = turn.id,
                        isError = turn.failed,
                    )
                }
            }

            val answer = try {
                executor.execute(request, model, step.tools)
            } catch (cause: Exception) {
                val failure = cause.message ?: cause::class.simpleName ?: "the model did not answer"
                log(AgentLine(step.id, round, AgentRole.ERROR, failure))

                return ConversationResult(said = null, rounds = round, usage = spent, failure = failure)
            }

            val usage = TokenUsage(
                input = answer.metaInfo.inputTokensCount,
                output = answer.metaInfo.outputTokensCount,
                reasoningCharacters = answer.thinkingText()?.length,
            )
            spent = spent?.plus(usage) ?: usage

            val thinking = answer.thinkingText()
            val spoken = answer.textContent().takeIf { it.isNotBlank() }
            val calls = answer.parts.filterIsInstance<MessagePart.Tool.Call>()

            if (thinking != null) {
                log(AgentLine(step.id, round, AgentRole.THINKING, thinking))
            }
            if (spoken != null) {
                log(AgentLine(step.id, round, AgentRole.ASSISTANT, spoken, usage.takeIf { calls.isEmpty() }))
            }

            // Nothing asked for is the way a conversation ends: the model has done what it came to
            // do and says so. An answer with neither words nor calls ends it too -- there is nothing
            // to carry out and nothing to reply to.
            if (calls.isEmpty()) {
                return ConversationResult(said = spoken, rounds = round, usage = spent)
            }

            turns += Turn.Said(answer)

            for (call in calls) {
                log(AgentLine(step.id, round, AgentRole.TOOL_CALL, "${call.tool} ${call.args}"))

                // The arguments as the model wrote them, or an empty object where it wrote something
                // that is not one: a tool that is handed nothing says what it needed, which is a
                // better round than an exception that ends the step.
                val arguments = runCatching { call.argsJson }.getOrNull() ?: JsonObject(emptyMap())
                val answered = run(call.tool, arguments)

                log(AgentLine(step.id, round, AgentRole.TOOL_RESULT, answered.text))
                turns += Turn.Answered(
                    id = call.id,
                    tool = call.tool,
                    text = answered.text,
                    failed = answered.failed,
                )
            }
        }

        val failure = "The step was still working after ${step.maxRounds} rounds and was stopped. " +
            "What it had already changed stands."
        log(AgentLine(step.id, step.maxRounds, AgentRole.ERROR, failure))

        return ConversationResult(
            said = null,
            rounds = step.maxRounds,
            usage = spent,
            failure = failure,
        )
    }

    /** One turn of a conversation as it is replayed into the next request. */
    private sealed interface Turn {
        /** What the model answered, tool calls and all, exactly as it came back. */
        data class Said(val answer: Message.Assistant) : Turn

        /** What one tool answered one of those calls. */
        data class Answered(
            val id: String?,
            val tool: String,
            val text: String,
            val failed: Boolean,
        ) : Turn
    }

    private suspend fun <T> ask(
        step: MailAnalysisStep<T>,
        context: MailContext,
        complaint: String?,
        attempt: Int,
        log: AgentLog,
    ): Attempt<T> {
        val model = LLModel(
            provider = provider,
            id = modelFor(step.tier),
            capabilities = capabilities,
        )

        // Built before the request rather than inside it, because they are logged as well as
        // sent, and a log of what was nearly sent is worth nothing.
        val mail = context.asMessage()
        // A repeat ask carries what was wrong with the one before it, so the model has something
        // to go on other than the same prompt again. The demand comes with it, because the answer
        // that has to be forced is almost always the same one: the object and nothing else.
        val objection = complaint?.let {
            "Your last answer was not usable: $it\n\n" +
                "Answer again, and answer with the JSON object for the requested structure and " +
                "nothing else: no explanation before it, no comment after it, no code fence " +
                "around it, no repetition of these instructions."
        }

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

            return Attempt(
                result = AnalysisResult(value = null, usage = null, failure = failure),
                answered = false,
            )
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

        // The object out of what was said, rather than what was said, see [answerJson]: a model
        // that thinks in the channel it answers in -- which the ones behind this backend do, told
        // not to or not -- puts its reasoning in front of the JSON, and the parser takes neither.
        // What went to the log above is untouched by this; it is the answer as it came.
        val json = answerJson(text)
            ?: return failed(step, attempt, usage, NO_JSON, log)

        return try {
            Attempt(AnalysisResult(value = step.structure.parse(json), usage = usage), answered = true)
        } catch (cause: Exception) {
            val why = cause.message ?: cause::class.simpleName ?: "it could not be read"
            failed(step, attempt, usage, "The answer was not the shape that was asked for: $why", log)
        }
    }

    /**
     * An ask the model answered with something that could not be used. The cost stands either way,
     * and the answer counts as given: [run] asks again with [failure] attached.
     */
    private suspend fun <T> failed(
        step: MailAnalysisStep<T>,
        attempt: Int,
        usage: TokenUsage,
        failure: String,
        log: AgentLog,
    ): Attempt<T> {
        log(AgentLine(step.id, attempt, AgentRole.ERROR, failure))

        return Attempt(
            result = AnalysisResult(value = null, usage = usage, failure = failure),
            answered = true,
        )
    }

    /** The model a tier resolves to. Falls back to the main one when the config names no fast one. */
    private fun modelFor(tier: ModelTier): String = when (tier) {
        ModelTier.FAST -> config.fastModel ?: config.model
        ModelTier.CAPABLE -> config.model
    }
}

/**
 * How long a step waits on the backend before it counts as unanswered.
 *
 * Stated rather than left to the client, whose own default is fifteen minutes on the request and
 * fifteen more on the socket. That is not a timeout anybody watching a run would recognise as one:
 * a request that goes into a hole -- a connection the other end has dropped without saying so, a
 * gateway that takes the prompt and never answers -- leaves the log sitting on the prompt it sent,
 * with no line after it and nothing to say the step is dead rather than slow. Both have happened
 * against this backend, mid-run, with the step before it answered in under a second.
 *
 * Two minutes because a step is one short answer, not a conversation: the fast steps come back in
 * seconds against a hosted backend, and the slowest case worth waiting for is a local one loading
 * the model on the first ask. Past that there is nothing coming, and a failed step says so -- see
 * [AnalysisResult.failure], which is what puts the reason on the wire.
 */
/**
 * How many times one step is asked before it is given up on: the first ask and five more.
 *
 * Five because the answers that fail are the ones that fail differently each time -- a paragraph
 * instead of an object, then the object with a field misread, then a fence around it -- and each
 * complaint is about the last of those rather than about the step. A model that keeps missing gets
 * a specific objection each time, which is what makes another ask worth the request.
 *
 * The ceiling is what stops a queue that is running over a mailbox from spending a minute of
 * requests on one mail nobody is waiting for. What comes out then is a failed step with the last
 * problem on it, see [AnalysisResult.failure], which is a line in the log rather than an exception.
 */
private const val MAX_ATTEMPTS = 6

private val TIMEOUTS = ConnectionTimeoutConfig(
    requestTimeoutMillis = 120_000,
    connectTimeoutMillis = 10_000,
    socketTimeoutMillis = 120_000,
)

/**
 * Why a step failed on an answer that had words in it but no object anywhere.
 *
 * Told apart from the shape being wrong on purpose: an answer that filled the schema with the
 * wrong thing is the model's reading of the mail, while this is the model not answering the ask at
 * all -- usually because it thought until [MailAnalysisStep.maxOutputTokens] ran out.
 */
private const val NO_JSON = "The model answered without any JSON in it, so there was nothing to read."

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
