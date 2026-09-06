package es.jvbabi.overmail.server.http.share

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * A shared mail as it looks to somebody who has just opened the link: `GET /api/shares/{shareId}`.
 *
 * No session: holding the link is the whole authorization, which is what a share is. What comes
 * back depends on the share -- the mail where no password was set, subject and sender where one
 * was but the metadata is open, and nothing but "a password is needed" otherwise. `openShare` is
 * the same answer with the password typed in.
 */
fun Route.getShare() {
    get {
        val share = call.requireLiveShareFromUrl()
        // A link without a password is open to whoever has it; there is nothing to type.
        val shared = call.readSharedEmail(share, unlocked = share.passwordHash == null)

        call.respond(shared)
    }
}
