package es.jvbabi.overmail.server.ai

import ai.koog.agents.core.tools.ToolDescriptor
import kotlinx.serialization.json.JsonObject

/**
 * A step that works by doing things rather than by filling in a shape.
 *
 * The other kind of step answers one question about one mail and is done, see [MailAnalysisStep].
 * This one is handed tools and a mailbox: it looks things up, decides what to change and changes it,
 * and the conversation goes on until it says it has finished. What comes back at the end is a
 * sentence, not an object -- the result of the step is what it did, and that is already in the
 * database by then.
 *
 * Its own type rather than a flag on the other, because almost nothing carries over. There is no
 * schema, so there is nothing to parse and nothing to validate; there is no single answer, so there
 * is nothing to re-ask for; and the rules it has to be given are the opposite ones -- a step that
 * must call tools cannot be told to answer with an object and nothing else. Which is also why it
 * writes its own system prompt whole instead of inheriting the shared rules.
 */
class MailToolStep(
    /** Identifies the prompt, and shows up in the log as the step every line belongs to. */
    val id: String,

    /** The whole system prompt, shared rules included: this step's rules are its own. */
    val systemPrompt: String,

    /** What it may do. A name the model calls that is not in here comes back as an error. */
    val tools: List<ToolDescriptor>,

    /** The capable model by default: deciding what to change about a mailbox is a judgement. */
    val tier: ModelTier = ModelTier.CAPABLE,

    /**
     * Hard ceiling on one answer of the conversation, not on the conversation. A round is a couple
     * of tool calls or a sentence saying it is done; a model that starts writing an essay instead
     * should cost one truncated round rather than a queue.
     */
    val maxOutputTokens: Int = 800,

    /**
     * How many rounds the conversation may take before it is cut off.
     *
     * A round is one answer and the tool results it asked for. Enough to look at a handful of mails
     * and act on them; past that the model is going round in circles, and what it has already done
     * stands either way -- the tools change the mailbox as they are called, not at the end.
     */
    val maxRounds: Int = 8,
)

/**
 * What one tool answered.
 *
 * [failed] is the difference between "here is what you asked for" and "that did not work": a tool
 * that refuses -- a mail that is not there, a thread the agent may not touch -- says so as an error,
 * and the model is expected to do something else rather than call it again.
 */
data class ToolAnswer(val text: String, val failed: Boolean = false)

/**
 * Where a tool call is actually carried out.
 *
 * The name and the arguments as the model wrote them, not a parsed shape: what a tool takes is its
 * own business, and the arguments a model got wrong are the tool's to complain about. Whoever
 * implements this is the one with the mailbox -- see the revision desk -- while [MailAnalyst] only
 * ever puts calls to it and their answers back on the wire.
 */
typealias ToolRunner = suspend (tool: String, arguments: JsonObject) -> ToolAnswer

/**
 * What a conversation came to.
 *
 * There is no value to hand back: whatever the step decided is in the database already, done by the
 * tools as they were called. What is here is how it went -- the last thing the model said, what the
 * rounds cost, and why it stopped where it did.
 */
data class ConversationResult(
    /** The model's closing words, null where it never got to any. */
    val said: String?,
    /** How many answers it took. */
    val rounds: Int,
    val usage: TokenUsage?,
    /** Why it ended other than by finishing: a backend that fell over, or the round cap. */
    val failure: String? = null,
)
