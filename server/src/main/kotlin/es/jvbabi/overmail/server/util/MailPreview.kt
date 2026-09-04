package es.jvbabi.overmail.server.util

/** How much of a mail travels as its preview. Long enough for a sentence, short enough for a row. */
const val MAIL_PREVIEW_LENGTH = 200

/** Cut inside the last word rather than this far from the end; below it the cut is exact. */
private const val WORD_BOUNDARY_SLACK = 24

/**
 * Characters that take no room on screen but plenty in a preview.
 *
 * Newsletters pad their beginning with these so that a mail client's own preview shows the
 * preheader they wrote and nothing else -- combining grapheme joiners, zero width spaces, figure
 * spaces by the hundred. Soft hyphens are the other half of it: they sit inside words ("Pass\u00ADwort")
 * and only a renderer that hyphenates is supposed to see them.
 *
 * `\p{Cf}` is the format category, which covers the soft hyphen, the zero width family and the
 * byte order mark; the grapheme joiner is a combining mark and has to be named.
 */
private val invisible = Regex("[\\p{Cf}\\u034F]")

/** Every kind of space, not the seven that Java calls whitespace: `\\s` misses U+00A0 and U+2007. */
private val spaces = Regex("[\\s\\p{Z}]+")

/** What is left of a body once nothing invisible is in the way: one line, or nothing at all. */
private fun readable(body: String): String =
    body.replace(invisible, "").replace(spaces, " ").trim()

/**
 * The first line of a mail, as a listing shows it next to the subject.
 *
 * Whatever the mail brought: its text part, or its HTML turned into text -- an HTML-only mail is
 * the common case and it has a beginning like any other. All whitespace collapses into single
 * spaces, because this is one line and the line breaks of a mail body are not its shape any more,
 * and everything invisible is dropped, see [invisible].
 *
 * Computed where a mail is written, not where it is read: the body is the largest thing a mail
 * has, and a listing of a hundred rows must not pull a hundred bodies through the database.
 *
 * Empty for a mail with nothing readable in it -- an empty preview is a mail that says nothing,
 * which is not the same as a mail nobody has looked at yet (that one is null in the column).
 */
fun mailPreview(text: String?, html: String?): String {
    // Cleaned before it is judged, not after: a text part that is nothing but padding reads as
    // present and would leave the preview empty while the HTML part has the actual beginning.
    val line = text?.let(::readable)?.takeIf { it.isNotEmpty() }
        ?: html?.let(HtmlToText::convert)?.let(::readable)?.takeIf { it.isNotEmpty() }
        ?: return ""

    if (line.length <= MAIL_PREVIEW_LENGTH) return line

    // Cut at the last space in reach, so the preview does not end mid-word. A line without one
    // that close -- a URL, a language that does not space its words -- is cut where it is.
    val cut = line.lastIndexOf(' ', MAIL_PREVIEW_LENGTH)
    val end = if (cut >= MAIL_PREVIEW_LENGTH - WORD_BOUNDARY_SLACK) cut else MAIL_PREVIEW_LENGTH

    return line.take(end).trimEnd() + "…"
}
