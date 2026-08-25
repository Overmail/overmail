package es.jvbabi.overmail.server.domain.spam

import es.jvbabi.overmail.server.domain.models.Email

/**
 * A mail as a rule reads it: the four parts a comparison can name, and nothing else.
 *
 * Kept apart from [Email] so that a rule can be held against a mail that is not a stored one --
 * an incoming one, or one made up in a test -- and so that what "the body" is gets decided in one
 * place, see [toRuleFacts].
 */
data class MailFacts(
    val subject: String,
    /** The display name the sender used, null for a bare address. */
    val senderName: String?,
    val senderAddress: String,
    val body: String,
)

/**
 * Whether a spam rule holds for a mail.
 *
 * Comparisons ignore case throughout, including the regexes: spam does not care about
 * capitalisation and the editor has no switch for it. A regex that has to be exact can say so
 * itself with `(?-i)`.
 *
 * A regex is compiled where it is read, so [matches] throws [IllegalArgumentException] for a
 * pattern no engine can compile. The editor refuses to build one, which leaves a hand-written
 * filter -- and for those, saying so beats reporting a mail as clean.
 */
class SpamRuleMatcher {

    /** Whether [rule] holds for a stored mail. */
    fun matches(rule: SpamRule, mail: Email): Boolean = matches(rule, mail.toRuleFacts())

    /** Whether [rule] holds for a mail, whatever it was read from. */
    fun matches(rule: SpamRule, mail: MailFacts): Boolean = when (rule) {
        // An operator over nothing says nothing, and a filter that says nothing must not catch
        // every mail there is -- which is what `all` on an empty list would have it do.
        is SpamRule.And -> rule.operands.isNotEmpty() && rule.operands.all { matches(it, mail) }
        is SpamRule.Or -> rule.operands.any { matches(it, mail) }
        is SpamRule.Not -> !matches(rule.operand, mail)
        is SpamRule.Match -> compare(rule, mail)
    }

    private fun compare(rule: SpamRule.Match, mail: MailFacts): Boolean {
        val text = when (rule.field) {
            SpamRuleField.SUBJECT -> mail.subject
            // A mail without a display name reads as one with an empty one: there is nothing to
            // compare, and every comparison the editor can build needs a non-empty text.
            SpamRuleField.SENDER_NAME -> mail.senderName.orEmpty()
            SpamRuleField.SENDER_ADDRESS -> mail.senderAddress
            SpamRuleField.BODY -> mail.body
        }

        return when (rule.match) {
            SpamRuleMatch.EQUALS -> text.equals(rule.value, ignoreCase = true)
            SpamRuleMatch.CONTAINS -> text.contains(rule.value, ignoreCase = true)
            // Anywhere in the text rather than the whole of it, which is what a regex written
            // against a mail is for -- `^` and `$` are there for whoever means the whole.
            SpamRuleMatch.REGEX -> Regex(rule.value, RegexOption.IGNORE_CASE).containsMatchIn(text)
        }
    }
}

/**
 * What a rule reads from a stored mail.
 *
 * The body is the plain text part, or the HTML part flattened when the mail carried no text --
 * the same choice the web app makes for what it shows (`web/src/lib/app/mails/body.ts`), so that a
 * rule is held against what the reader sees rather than against markup nobody reads. Keep the two
 * in step, or "Inhalt enthält" will mean two different things.
 */
fun Email.toRuleFacts() = MailFacts(
    subject = subject,
    senderName = senderName,
    senderAddress = sender.address,
    body = textContent?.trim()?.takeIf { it.isNotEmpty() } ?: htmlContent?.flattenHtml() ?: "",
)

/** Tags whose content is markup or code rather than something to read. */
private val DROPPED_ELEMENTS = Regex("""<(script|style|head|title)\b[^>]*>.*?</\1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

/** Tags that end a line where they sit, so the flattened text keeps the mail's paragraphs. */
private val LINE_BREAKING = Regex("""<(?:br|/p|/div|/tr|/li|/h[1-6]|/blockquote)\b[^>]*>""", RegexOption.IGNORE_CASE)

private val TAGS = Regex("<[^>]*>")

/** The five that have to be escaped in HTML, plus the space that pads half of all mail layouts. */
private val ENTITIES = mapOf(
    "amp" to "&",
    "lt" to "<",
    "gt" to ">",
    "quot" to "\"",
    "apos" to "'",
    "nbsp" to " ",
)

private val ENTITY = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")

/** Runs of whitespace that every layout table leaves behind where its cells were. */
private val HORIZONTAL_RUNS = Regex("""[^\S\n]+""")
private val PADDED_BREAKS = Regex(""" ?\n ?""")
private val BLANK_RUNS = Regex("""\n{3,}""")

private fun String.flattenHtml(): String =
    replace(DROPPED_ELEMENTS, "")
        .replace(LINE_BREAKING, "\n")
        .replace(TAGS, "")
        .decodeEntities()
        .replace(HORIZONTAL_RUNS, " ")
        .replace(PADDED_BREAKS, "\n")
        .replace(BLANK_RUNS, "\n\n")
        .trim()

private fun String.decodeEntities(): String = ENTITY.replace(this) { match ->
    val body = match.groupValues[1]

    when {
        body.startsWith("#x") || body.startsWith("#X") ->
            body.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: match.value

        body.startsWith("#") -> body.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: match.value
        else -> ENTITIES[body.lowercase()] ?: match.value
    }
}
