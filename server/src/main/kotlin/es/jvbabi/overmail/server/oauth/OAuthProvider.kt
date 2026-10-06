package es.jvbabi.overmail.server.oauth

/**
 * A mail provider an inbox can be connected through by signing in there instead of typing an imap
 * password.
 *
 * Adding one is an entry here and a client in the `oauth` section of the config; the routes, the
 * list the dialog shows and the state handling are the same for all of them.
 */
enum class OAuthProvider(
    /** What the routes and the config file name it by. A client branches on it, so it stays stable. */
    val id: String,
    val authorizeUrl: String,
    val tokenUrl: String,
    val scopes: List<String>,
    /** What this provider needs on the authorize request beyond the standard parameters. */
    val authorizeParameters: Map<String, String> = emptyMap(),
) {
    MICROSOFT(
        id = "microsoft",
        authorizeUrl = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
        tokenUrl = "https://login.microsoftonline.com/common/oauth2/v2.0/token",
        // offline_access is what brings a refresh token -- the importer keeps logging in long after
        // the access token from this sign-in ran out. openid and email name the mailbox.
        scopes = listOf("https://outlook.office.com/IMAP.AccessAsUser.All", "offline_access", "openid", "email"),
    ),
    GOOGLE(
        id = "google",
        authorizeUrl = "https://accounts.google.com/o/oauth2/v2/auth",
        tokenUrl = "https://oauth2.googleapis.com/token",
        scopes = listOf("https://mail.google.com/", "openid", "email"),
        // Google hands out a refresh token only for offline access, and only on the first consent
        // unless it is asked for again -- a mailbox that was connected once before would get none.
        authorizeParameters = mapOf("access_type" to "offline", "prompt" to "consent"),
    );

    companion object {
        fun byId(id: String): OAuthProvider? = entries.firstOrNull { it.id == id }
    }
}
