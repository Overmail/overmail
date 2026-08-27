package es.jvbabi.overmail.server.ai

/**
 * How much of one mail a step is handed whole. Past this it is cut, see [readableBody].
 *
 * Roughly two thousand tokens, which is more than any mail worth reading needs to be understood
 * and far less than a marketing mail will happily spend.
 */
private const val BUDGET = 8_000

/** What is kept from the top: the greeting, what the mail is about, usually the signature too. */
private const val HEAD = 5_000

/**
 * What is kept from the bottom.
 *
 * A cut from the top alone would be the wrong end to keep. The last lines of a mail are where the
 * signature, the imprint and the platform's own footer sit -- "Diese Nachricht wurde über LernSax
 * versendet", "You are receiving this because you are registered at X" -- and those lines are what
 * the sender step reads to name the organisation and the platform at all.
 */
private const val TAIL = 2_000

/** How far a cut looks for a line break rather than landing mid-sentence. */
private const val LINE_SEARCH = 500

/**
 * A link, whatever it has been decorated with. Everything up to the first whitespace or bracket:
 * tracking parameters do not contain either, and a mail that puts a link in brackets means the
 * brackets to be punctuation.
 */
private val URL = Regex("""https?://[^\s<>"'\[\]()]+""")

/**
 * The mail's text cut down to what is worth handing a model.
 *
 * The text arriving here has already been flattened -- the plain part, or the HTML with its markup
 * taken out, see `mailFactsOf`. What is left is still mostly cost: a newsletter's links carry a
 * hundred characters of tracking each, and a mail nobody would read to the end is handed over in
 * full. Two cuts, both of which leave what a step actually reads:
 *
 * - A link becomes its host. `https://www.lernsax.de/wws/9.php?sid=42#nav` is `lernsax.de`, which
 *   is the part that says anything about where a mail comes from; the rest is a session nobody but
 *   the sender's server can use.
 * - A mail past [BUDGET] keeps its head and its tail with a line saying what fell out between
 *   them. Said out loud rather than trimmed silently: a model handed a mail that stops mid-sentence
 *   otherwise reads the stop as the mail's own.
 *
 * Deliberately not dropped: the unsubscribe block, the legal footer, "you are receiving this
 * because". Those read as boilerplate, and they are exactly the lines that name the platform a
 * mail came through.
 */
fun readableBody(text: String): String {
    val shortened = text.replace(URL) { it.value.hostOnly() }

    if (shortened.length <= BUDGET) return shortened

    val head = shortened.take(HEAD).toLastBreak()
    val tail = shortened.takeLast(TAIL).fromFirstBreak()
    val left = shortened.length - head.length - tail.length

    return "$head\n\n[... $left characters left out ...]\n\n$tail"
}

/**
 * How many links of one mail are worth listing whole, see [mailLinks].
 *
 * A mail with more links than this is a newsletter, and the one link a reader is let in by is not
 * the thirty-first of a page of them.
 */
private const val MAX_LINKS = 30

/** Punctuation a link picks up from the sentence it sits in rather than carrying itself. */
private val TRAILING_PUNCTUATION = charArrayOf('.', ',', ';', ':', '!', '?')

/**
 * The links the mail carries, whole, each once, in the order they appear; at most [MAX_LINKS].
 *
 * Read off the same flattened text [readableBody] is handed, and needed *because* of what that
 * function does to it: a link in the body arrives at the model as its host, which is all a step
 * reading where a mail comes from wants and nothing at all to a step that has to hand a link back.
 * A sign-in link is only a way in while it is whole -- the grant is in the query -- so the whole
 * ones are kept here, beside the body rather than in it, and the body stays as cheap as it was.
 *
 * Listed rather than searched for, so an answer can point at one of these instead of copying
 * several hundred characters of signed token and getting a character of it wrong.
 *
 * Trailing punctuation is dropped: a link at the end of a sentence takes the full stop with it,
 * which is not part of it and is enough to make it 404.
 */
fun mailLinks(text: String): List<String> = URL.findAll(text)
    .map { it.value.trimEnd(*TRAILING_PUNCTUATION) }
    // A link that was nothing but a scheme is not one, and duplicates are the norm: the same
    // sign-in link stands under the button and again in the "if that does not work" line.
    .filter { it.length > "https://".length }
    .distinct()
    .take(MAX_LINKS)
    .toList()

/** A link as just its host: no scheme, no `www.`, no path, no query. */
private fun String.hostOnly(): String = substringAfter("://")
    .substringBefore('/')
    .substringBefore('?')
    .substringBefore('#')
    .removePrefix("www.")

/** Cut back to the last line break, where there is one close enough to be the end of a line. */
private fun String.toLastBreak(): String {
    val breakAt = lastIndexOf('\n')

    return if (breakAt >= length - LINE_SEARCH) substring(0, breakAt) else this
}

/** The same from the other end: forward to the first line break close enough to be one. */
private fun String.fromFirstBreak(): String {
    val breakAt = indexOf('\n')

    return if (breakAt in 0..LINE_SEARCH) substring(breakAt + 1) else this
}
