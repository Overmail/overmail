package es.jvbabi.overmail.page.email

import es.jvbabi.overmail.domain.model.Email
import es.jvbabi.overmail.domain.model.Share
import io.ktor.http.encodeURLPathPart
import kotlin.uuid.Uuid

/** How much of the subject a url carries, the web app's `SUBJECT_LIMIT`. */
private const val SUBJECT_LIMIT = 64

private val WHITESPACE = Regex("""\s+""")

/**
 * Where [email] lives in the web app, the page this one is to the app: the web app's `emailPath`
 * on the homeserver, which serves both. The subject in front of the id is for whoever reads the
 * link; the web app only reads the id at its end. Only the owner of the mail can open it.
 */
fun emailWebUrl(email: Email): String =
    "${email.overmailAccount.homeserver.trimEnd('/')}/emails/${slugSegment(email.id, email.subject)}"

/**
 * The link [share] hands out, the web app's `shareUrl`: `/share/<subject>-<id>` on [homeserver].
 *
 * A url is read by everything it passes through -- a chat, a proxy log, someone looking over a
 * shoulder -- none of which type the password. So the [subject] only goes in where a visitor is
 * shown it anyway: no password at all, or one that leaves the metadata open.
 */
fun shareWebUrl(homeserver: String, share: Share, subject: String?): String {
    val visibleSubject = subject.takeUnless { share.hasPassword && !share.allowMetadataWithoutPassword }
    return "${homeserver.trimEnd('/')}/share/${slugSegment(share.id, visibleSubject)}"
}

/** The web app's `slugSegment`: the readable beginning of [subject], then the bare id. */
private fun slugSegment(id: Uuid, subject: String?): String {
    val bareId = id.toString().replace("-", "")
    val slug = subject.orEmpty()
        .take(SUBJECT_LIMIT)
        // Not cut through the middle of a character that takes two.
        .let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
        .trim()
        .replace(WHITESPACE, "-")
        .trim('-')
    return if (slug.isEmpty()) bareId else "${slug.encodeURLPathPart()}-$bareId"
}
