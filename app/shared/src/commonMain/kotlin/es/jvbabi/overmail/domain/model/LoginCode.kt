package es.jvbabi.overmail.domain.model

import es.jvbabi.overmail.BuildKonfig
import io.ktor.http.decodeURLPart
import io.ktor.http.hostWithPortIfSpecified
import io.ktor.http.parseUrl

/** A one-time code from the web app's device settings that signs the app in to [serverUrl]. */
data class LoginCode(
    val serverUrl: String,
    val code: String,
) {
    companion object {
        private const val SCHEME = "overmail://"

        /**
         * Reads what the web app puts into its QR code,
         * `overmail://<encoded server origin>/auth?code=<code>` -- see `createAuthCode` on the
         * server. The origin is one percent-encoded segment rather than a second `https://` in
         * the middle, so the whole thing stays a single url.
         *
         * A bare code, as typed by hand, is taken to be for the default server.
         */
        fun parse(value: String): LoginCode? {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) return null
            if (!trimmed.startsWith(SCHEME)) {
                if (trimmed.any { it.isWhitespace() || it == '/' }) return null
                return LoginCode(serverUrl = BuildKonfig.SERVER_URL, code = trimmed)
            }

            // Everything up to the first slash is the origin; the rest is a path and a query that
            // only mean anything once they are put back behind it.
            val rest = trimmed.removePrefix(SCHEME)
            val pathStart = rest.indexOf('/')
            if (pathStart <= 0) return null

            val origin = rest.substring(0, pathStart).decodeURLPart()
            val url = parseUrl(origin + rest.substring(pathStart)) ?: return null

            val code = url.parameters["code"]?.takeIf { it.isNotBlank() } ?: return null
            if (url.encodedPath != "/auth") return null

            return LoginCode(
                serverUrl = "${url.protocol.name}://${url.hostWithPortIfSpecified}",
                code = code,
            )
        }
    }
}
