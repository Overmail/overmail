package es.jvbabi.overmail.domain.intent

import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import kotlin.uuid.Uuid

/**
 * Something the app is asked to do from outside its own screens: a notification that was tapped,
 * later a shortcut or a link.
 *
 * Every intent has a uri, and the uri is how it travels -- as the data of an Android `Intent`, as
 * the url an iOS scene is opened with. So whatever can carry a link can carry an intent, and the
 * platform code never knows what one means: it hands the uri to [AppIntents] and is done.
 *
 * A new thing the app can be asked for is a subclass here, its path in [toUri] and [parse], and a
 * branch where `App` acts on it.
 */
sealed class AppIntent {

    /** Show the mail [emailId] of the account [accountId] on its page. */
    data class OpenEmail(val accountId: Uuid, val emailId: Uuid) : AppIntent()

    fun toUri(): String = URLBuilder("$SCHEME://$HOST").apply {
        when (this@AppIntent) {
            is OpenEmail -> appendPathSegments(ACCOUNTS, accountId.toString(), EMAILS, emailId.toString())
        }
    }.buildString()

    companion object {
        /** The scheme and host the app is registered for, see the manifest and Info.plist. */
        const val SCHEME = "overmailapp"
        const val HOST = "application"

        private const val ACCOUNTS = "accounts"
        private const val EMAILS = "emails"

        /**
         * The intent [uri] stands for, or null for anything else that reaches the app under its
         * scheme -- the return from the sign-in, a link from a newer version.
         */
        fun parse(uri: String): AppIntent? {
            val url = runCatching { Url(uri) }.getOrNull() ?: return null
            if (url.protocol.name != SCHEME || url.host != HOST) return null

            val path = url.segments
            return when {
                path.size == 4 && path[0] == ACCOUNTS && path[2] == EMAILS -> OpenEmail(
                    accountId = Uuid.parseOrNull(path[1]) ?: return null,
                    emailId = Uuid.parseOrNull(path[3]) ?: return null,
                )
                else -> null
            }
        }
    }
}
