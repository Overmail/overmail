package es.jvbabi.overmail.server.http.users.me.inboxes.create.oauth

import es.jvbabi.overmail.core.ImapClient
import es.jvbabi.overmail.server.http.api.requireOwnedOAuthOnboardingFromUrl
import es.jvbabi.overmail.server.http.users.me.inboxes.create.folders.scanMailbox
import io.ktor.http.ContentType
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * The folder scan of "new inbox" for a mailbox signed in to at a provider. The same events as
 * `streamInboxFolders`, but nothing to send: host, address and bearer all come with the onboarding,
 * so a GET does, and the bearer never travels through the browser.
 */
fun Route.streamOAuthOnboardingFolders() {
    authenticate {
        /**
         * Scan the folders of a mailbox signed in to at a provider.
         *
         * Description: The events of `POST /api/users/me/inboxes/create/folders/stream`, logged in with the bearer of the sign-in.
         *
         * Tag: Setup
         *
         * Responses:
         *   - 200 text/event-stream [es.jvbabi.overmail.server.http.users.me.inboxes.create.folders.FolderStreamEvent] The events
         */
        get {
            val onboarding = call.requireOwnedOAuthOnboardingFromUrl()
            call.respondTextWriter(ContentType.Text.EventStream) {
                scanMailbox(
                    writer = this,
                    host = onboarding.provider.imapHost,
                    port = onboarding.provider.imapPort,
                    // The access token of the sign-in, which lasts an hour -- far longer than the
                    // dialog it is scanned for stays open.
                    auth = ImapClient.Auth.BearerAuth(onboarding.address, onboarding.tokens.accessToken),
                )
            }
        }
    }
}
