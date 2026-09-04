package es.jvbabi.overmail.server.util

/** How much of a mail travels as its preview. Long enough for a sentence, short enough for a row. */
const val MAIL_PREVIEW_LENGTH = 200

/** Cut inside the last word rather than this far from the end; below it the cut is exact. */
private const val WORD_BOUNDARY_SLACK = 24

private val whitespace = Regex("\\s+")

/**
 * The first line of a mail, as a listing shows it next to the subject.
 *
 * Whatever the mail brought: its text part, or its HTML turned into text -- an HTML-only mail is
 * the common case and it has a beginning like any other. All whitespace collapses into single
 * spaces, because this is one line and the line breaks of a mail body are not its shape any more.
 *
 * Computed where a mail is written, not where it is read: the body is the largest thing a mail
 * has, and a listing of a hundred rows must not pull a hundred bodies through the database.
 *
 * Empty for a mail with nothing readable in it -- an empty preview is a mail that says nothing,
 * which is not the same as a mail nobody has looked at yet (that one is null in the column).
 */
fun mailPreview(text: String?, html: String?): String {
    val body = text?.takeIf { it.isNotBlank() }
        ?: html?.takeIf { it.isNotBlank() }?.let(HtmlToText::convert)
        ?: return ""

    val line = body.replace(whitespace, " ").trim()
    if (line.length <= MAIL_PREVIEW_LENGTH) return line

    // Cut at the last space in reach, so the preview does not end mid-word. A line without one
    // that close -- a URL, a language that does not space its words -- is cut where it is.
    val cut = line.lastIndexOf(' ', MAIL_PREVIEW_LENGTH)
    val end = if (cut >= MAIL_PREVIEW_LENGTH - WORD_BOUNDARY_SLACK) cut else MAIL_PREVIEW_LENGTH

    return line.take(end).trimEnd() + "…"
}
