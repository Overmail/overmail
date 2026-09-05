package es.jvbabi.overmail.server.ai.chat

/**
 * How a chat gets its name: the prompt of the naming call, and everything a name has to survive
 * before it is written.
 *
 * The agent is not the only one writing here -- `rename_chat` writes a name mid-answer, and it
 * goes through the same cleanup: a name a model came up with on its own is no tidier than one it
 * was asked for.
 */

internal const val NAME_PROMPT =
    "You give chats in a mail app their name. What follows is the first exchange of one " +
        "chat: answer with its name and nothing else -- never answer the message " +
        "yourself, never continue the conversation, and never write a sentence about " +
        "the name.\n" +
        "Name the chat after what the user wanted, not after how the answer turned out: " +
        "the name stays while the chat goes on, and the first sentence of an answer is " +
        "not a name. Write a short phrase of at most five words, without a final full " +
        "stop, quotes or markup.\n" +
        "The user has many chats and tells them apart by their names alone, so be " +
        "concrete: put the thing the chat is about into it -- a sender, a subject, a " +
        "label, a booking -- instead of naming the kind of task on its own. Write it in " +
        "the language the user wrote in; the examples below are German only because " +
        "that user writes German.\n" +
        "What the user attached appears as [email], [label] or [sender]: never write " +
        "those into the name, and never write an id, or the user's own name or email " +
        "address unless the chat is about them.\n" +
        "Names: \"Ticket für die Erstitage\", \"Steam-Rabatt für Iberia\", " +
        "\"Studium-Label für Uni und HPI\". Not names: \"Frage zu einer Mail\" (says " +
        "nothing), \"Suche\" (too vague), \"Ja, dein Abo ist gekündigt\" (that is the " +
        "answer, not the topic)."

/** The column holds 255 characters, and a title that long is not a title. */
internal const val MAX_CHAT_NAME_LENGTH = 60

/**
 * How much of each side of the exchange the naming call gets. A name comes from what was
 * asked and from the first lines of the answer; the rest is detail nobody titles a chat
 * after, and it is paid for per run.
 */
private const val MAX_NAME_INPUT_LENGTH = 800

/** Reasoning, including a block left open by a run that ended inside one. */
private val THINKING_ELEMENT =
    Regex("${ChatAgent.THINKING_START}.*?(?:${ChatAgent.THINKING_END}|\\z)", RegexOption.DOT_MATCHES_ALL)

/** Every other element an answer is rendered with: the tool calls and the ones standing for an email or a label. */
private val ANSWER_ELEMENT = Regex("</?[a-zA-Z][^<>]*>")

/** What the user attached, written as an id the naming model can do nothing with. */
private val REFERENCE = Regex("\\[(email|label|sender):[^\\[\\]]*]")

/**
 * The rendering taken back out of a text: markup says nothing about what a chat is about,
 * and a model that is shown an element puts one in the name -- which is how a chat ends
 * up called `<toolcall-search-emails></toolcall-search-emails>`.
 */
internal fun stripChatMarkup(text: String): String = text
    .replace(THINKING_ELEMENT, "")
    .replace(ANSWER_ELEMENT, "")
    .replace(REFERENCE) { match -> "[${match.groupValues[1]}]" }
    .replace(Regex("[ \\t]+"), " ")
    .replace(Regex("\\n{3,}"), "\n\n")
    .trim()

/**
 * The exchange as the naming model sees it, with the closing line repeating what it is
 * for: a model handed a question and an answer otherwise answers the question again.
 */
internal fun chatNamingInput(request: String, answer: String): String = buildString {
    append("Message:\n").append(stripChatMarkup(request).take(MAX_NAME_INPUT_LENGTH)).append("\n\n")
    append("Answer:\n").append(stripChatMarkup(answer).take(MAX_NAME_INPUT_LENGTH)).append("\n\n")
    append("Name this chat. Answer with the name alone.")
}

/** `Titel:`, `Name -` and whatever else a model puts in front of the name it was asked for. */
private val NAME_LABEL = Regex("^(?:title|titel|name|chat ?name)\\s*[:\\-]\\s*", RegexOption.IGNORE_CASE)

/** Quotes, markdown and the punctuation a sentence ends with -- none of it belongs in a name. */
private val NAME_TRIM_CHARS = charArrayOf(
    '"', '\'', '“', '”', '„', '`', '*', '#', '.', ',', ';', ':', '-', '–', ' ',
)

/**
 * Models like to wrap a title in quotes or add a line about it, so only the first line is
 * kept. Null when nothing usable is left.
 */
internal fun cleanChatName(name: String): String? = stripChatMarkup(name)
    .lineSequence()
    .firstOrNull { line -> line.isNotBlank() }
    ?.trim()
    ?.replace(NAME_LABEL, "")
    ?.trim(*NAME_TRIM_CHARS)
    ?.shortenToName()
    ?.takeIf { it.isNotBlank() }

/**
 * Cut on a word boundary: the answer of a model that ignored "at most five words" still
 * has to fit, and a name broken mid-word reads like a bug. A first word longer than the
 * limit is cut where it is -- there is no boundary to use.
 */
private fun String.shortenToName(): String {
    if (length <= MAX_CHAT_NAME_LENGTH) return this
    val cut = take(MAX_CHAT_NAME_LENGTH)
    val boundary = cut.lastIndexOf(' ')
    return (if (boundary > 0) cut.take(boundary) else cut).trimEnd(*NAME_TRIM_CHARS)
}
