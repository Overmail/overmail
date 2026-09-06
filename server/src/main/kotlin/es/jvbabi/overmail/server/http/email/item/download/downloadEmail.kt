package es.jvbabi.overmail.server.http.email.item.download

import es.jvbabi.overmail.server.http.api.requireOwnedEmailFromUrl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.auth.authenticate
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The mail as it arrived: `GET /api/emails/{emailId}/download`.
 *
 * The stored RFC 5322 source, byte for byte -- not the parsed text or html, which is what
 * `/body` hands out. It is the one answer here that a mail client can be pointed at, so it goes
 * out as an attachment rather than something a browser would try to render.
 *
 * Deliberately uncached: unlike `/body` this is a file the reader saves, and a copy sitting in
 * the browser cache is a copy of the whole mail on disk for no gain -- it is fetched once.
 */
fun Route.downloadEmail() {
    authenticate {
        get {
            val email = call.requireOwnedEmailFromUrl()

            // The subject names the file, cut down to what survives a header and a filesystem:
            // ascii letters, digits and a few separators, everything else becomes an underscore.
            // Spelled out here rather than in a helper, see the openapi plugin note in http/api/.
            val name = StringBuilder()
            for (character in email.subject) {
                val keep = character.code < 128 &&
                        (character.isLetterOrDigit() || character == ' ' || character == '-' || character == '_')
                name.append(if (keep) character else '_')
            }
            val subject = name.toString().trim(' ', '_', '.').take(120).ifEmpty { "email" }

            // The day the mail was sent in front, so a folder of these sorts by date on its own.
            // In the reader's zone rather than UTC, which is the day the listing files it under
            // too -- see the groups on the home screen.
            val day = email.sent.toLocalDateTime(TimeZone.currentSystemDefault()).date
            val fileName = "${day}_$subject"

            // Spelled out rather than through ContentDisposition, which leaves the name bare when
            // nothing in it needs quoting -- both forms are legal, and a client that reads this
            // header should not have to handle two. Quoting is safe unconditionally: the name is
            // ascii with no quote or backslash left in it by the loop above.
            call.response.header(HttpHeaders.ContentDisposition, """attachment; filename="$fileName.eml"""")
            call.respondBytes(email.rawContent, ContentType("message", "rfc822"))
        }
    }
}
