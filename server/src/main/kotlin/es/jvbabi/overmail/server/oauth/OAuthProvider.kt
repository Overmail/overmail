package es.jvbabi.overmail.server.oauth

/**
 * A mail provider an inbox can be connected through by signing in there instead of typing an imap
 * password.
 *
 * Adding one is an entry here and a client in the `oauth` section of the config; the routes, the
 * list the dialog shows and the sign-in flow are the same for all of them.
 */
enum class OAuthProvider(
    /** What the routes and the config file name it by. A client branches on it, so it stays stable. */
    val id: String,
    val endpoints: OAuthEndpoints,
    val scopes: List<String>,
    /** Where the mailbox behind a sign-in here is read from, over imap with tls. */
    val imapHost: String,
    val imapPort: Int = 993,
    /** What this provider needs on the authorize request beyond the standard parameters. */
    val authorizeParameters: Map<String, String> = emptyMap(),
) {
    MICROSOFT(
        id = "microsoft",
        endpoints = OAuthEndpoints(
            authorize = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
            token = "https://login.microsoftonline.com/common/oauth2/v2.0/token",
            jwks = "https://login.microsoftonline.com/common/discovery/v2.0/keys",
            // Every tenant signs as itself, and /common takes them all, so there is no one issuer.
            issuer = null,
        ),
        // offline_access is what brings a refresh token -- the importer keeps logging in long after
        // the access token from this sign-in ran out. openid and email name the mailbox, in the id
        // token: the access token is Outlook's, which Graph's user info endpoint does not take.
        scopes = listOf("https://outlook.office.com/IMAP.AccessAsUser.All", "offline_access", "openid", "email"),
        imapHost = "outlook.office365.com",
    ),
    GOOGLE(
        id = "google",
        endpoints = OAuthEndpoints(
            authorize = "https://accounts.google.com/o/oauth2/v2/auth",
            token = "https://oauth2.googleapis.com/token",
            jwks = "https://www.googleapis.com/oauth2/v3/certs",
            issuer = "https://accounts.google.com",
        ),
        scopes = listOf("https://mail.google.com/", "openid", "email"),
        imapHost = "imap.gmail.com",
        // Google hands out a refresh token only for offline access, and only on the first consent
        // unless it is asked for again -- a mailbox that was connected once before would get none.
        authorizeParameters = mapOf("access_type" to "offline", "prompt" to "consent"),
    );

    companion object {
        fun byId(id: String): OAuthProvider? = entries.firstOrNull { it.id == id }
    }
}

/** Where a provider signs in, hands out tokens and publishes the keys its id tokens are signed with. */
data class OAuthEndpoints(
    val authorize: String,
    val token: String,
    val jwks: String,
    /** What the id token has to name as its `iss`; null where it differs per account. */
    val issuer: String?,
)
