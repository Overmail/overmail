package es.jvbabi.overmail.page.email

import es.jvbabi.overmail.domain.model.Email
import io.ktor.http.encodeURLPathPart

/** How much of the subject a url carries, the web app's `SUBJECT_LIMIT`. */
private const val SUBJECT_LIMIT = 64

private val WHITESPACE = Regex("""\s+""")

/**
 * Where [email] lives in the web app, the page this one is to the app: the web app's `emailPath`
 * on the homeserver, which serves both. The subject in front of the id is for whoever reads the
 * link; the web app only reads the id at its end. Only the owner of the mail can open it.
 */
fun emailWebUrl(email: Email): String {
    val bareId = email.id.toString().replace("-", "")
    val subject = email.subject.orEmpty()
        .take(SUBJECT_LIMIT)
        // Not cut through the middle of a character that takes two.
        .let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
        .trim()
        .replace(WHITESPACE, "-")
        .trim('-')
    val slug = if (subject.isEmpty()) bareId else "${subject.encodeURLPathPart()}-$bareId"
    return "${email.overmailAccount.homeserver.trimEnd('/')}/emails/$slug"
}
