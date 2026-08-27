package es.jvbabi.overmail.server.ai

/**
 * A whole thinking block, opened and closed. `<think>`, and `<thinking>` for the backends that
 * spell it out; case-insensitive, because a model that is improvising the tag improvises the case
 * too. Non-greedy and across line breaks: a model that thinks twice writes two blocks, and every
 * one of them is lines rather than a line.
 */
private val THINKING_BLOCK = Regex(
    """<(think|thinking)>.*?</\1>""",
    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
)

/**
 * A closing thinking tag with nothing that opened it.
 *
 * Its own case because it is the common one, not an odd one: a backend that lifts the reasoning
 * into its own field usually takes the opening tag with it and leaves the closing one standing in
 * the text. What comes after the last of these is the answer.
 */
private val THINKING_END = Regex("""</(think|thinking)>""", RegexOption.IGNORE_CASE)

/**
 * The JSON object out of what a model answered, or null where there is none in it.
 *
 * The steps ask for a schema and the backends are told to hold the model to it, and a model still
 * answers with a paragraph and then the JSON. `/no_think` is a request, not a switch: the shared
 * rules ask for it at the very top and this backend's model thinks anyway, out loud, in the same
 * channel it answers in. Koog's own parse strips a code fence and hands the rest to the
 * deserializer, so one line of reasoning in front of the object throws the whole answer away --
 * a step that got a perfectly good answer reports it as the wrong shape.
 *
 * So the answer is cut down to the object before anything parses it:
 *
 * - Thinking that is marked as thinking goes first, whole blocks and a stray closing tag both.
 * - What is left is read from its first `{` to the brace that closes it, which is what drops
 *   reasoning that was never marked as any -- a lead-in, a "Here is the JSON:", a sentence after
 *   the object explaining it.
 *
 * Null where that finds nothing: a model that spent its whole allowance thinking, or answered in
 * prose alone. That is the step failing rather than an answer to fix up, and it says so.
 *
 * What the log shows is untouched by this -- the answer goes in there as it came, see
 * [AgentRole.ASSISTANT]. This is only what gets parsed.
 */
fun answerJson(answer: String): String? {
    val spoken = answer.replace(THINKING_BLOCK, "").afterLastThinkingEnd()

    return spoken.firstJsonObject()
}

/**
 * What follows the last closing thinking tag, or the whole text where there is none.
 *
 * The last rather than the first: a model that thought twice leaves two of them, and the answer is
 * after all of the thinking rather than after the first bit of it.
 */
private fun String.afterLastThinkingEnd(): String {
    val end = THINKING_END.findAll(this).lastOrNull() ?: return this

    return substring(end.range.last + 1)
}

/**
 * From the first `{` to the brace that closes it, or null where the text has no such pair.
 *
 * Counted rather than matched with a pattern, because the shape being looked for is not one a
 * regular expression can describe: an object holds objects, and a `}` inside a string is a
 * character rather than an end -- `{"context": ["gh:acme/widgets#412}"]}` is one object and the
 * first `}` in it closes nothing.
 *
 * An object that never closes is no object: a model cut off by [MailAnalysisStep.maxOutputTokens]
 * mid-answer leaves half of one, and half of one is not worth handing to a parser that would only
 * report it as broken JSON.
 */
private fun String.firstJsonObject(): String? {
    val start = indexOf('{')
    if (start < 0) return null

    var depth = 0
    var inString = false
    var escaped = false

    for (at in start..lastIndex) {
        when {
            // Whatever a backslash was in front of is a character and nothing else, quote included.
            escaped -> escaped = false
            inString && this[at] == '\\' -> escaped = true
            this[at] == '"' -> inString = !inString
            inString -> Unit
            this[at] == '{' -> depth++
            this[at] == '}' -> {
                depth--
                if (depth == 0) return substring(start, at + 1)
            }
        }
    }

    return null
}
