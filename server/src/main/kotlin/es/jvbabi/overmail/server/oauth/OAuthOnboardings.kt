package es.jvbabi.overmail.server.oauth

import es.jvbabi.authentikt.core.AuthentiktInstance
import es.jvbabi.authentikt.core.AuthentiktUser
import es.jvbabi.authentikt.core.installAuthentikt
import es.jvbabi.authentikt.core.session.Session
import es.jvbabi.authentikt.core.session.sessions
import es.jvbabi.authentikt.core.step.BaseState
import es.jvbabi.authentikt.core.step.plugins.BasePlugin
import es.jvbabi.authentikt.core.step.plugins.builtin.OIDCPlugin
import es.jvbabi.authentikt.core.step.plugins.builtin.OIDCPluginState
import es.jvbabi.authentikt.core.step.plugins.builtin.OIDCTokens
import es.jvbabi.authentikt.core.step.plugins.builtin.UserInfo
import io.ktor.http.URLBuilder
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.routing.Route
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Mounted below the `/api` prefix Caddy forwards. The callback of each provider ends up at
 * `/api/inbox-sign-in/authentikt/static/plugins/authentikt-builtin/oidc/<provider>/callback`, which
 * is what it has to be registered with there; authentikt logs it at startup.
 */
const val INBOX_SIGN_IN_API_PREFIX = "/api/inbox-sign-in"

/** A mailbox somebody signed in to at a provider. */
data class OAuthMailbox(
    val provider: OAuthProvider,
    /** The address the provider named; what imap logs in as. */
    val address: String,
    val tokens: OIDCTokens,
)

private val OWNER = AttributeKey<Uuid>("overmail.oauth-onboarding.owner")
private val PROVIDER = AttributeKey<OAuthProvider>("overmail.oauth-onboarding.provider")

/**
 * The sign-ins "new inbox" sends the browser off to, as a flow of their own next to the login.
 *
 * A flow is started by a signed-in user for one provider, and its first step is that provider's
 * [OIDCPlugin]: it sends the browser there, trades the code for tokens and checks the id token. The
 * mailbox it came back with then waits in the flow, at [FoldersStep], while the dialog the browser
 * is sent back to picks the folders. Neither the tokens nor the flow ever leave the server; the
 * browser carries the flow's id, and only its owner gets anything for it.
 */
class OAuthOnboardings internal constructor(private val instance: AuthentiktInstance<OAuthMailbox>) {

    /** Starts [userId]'s sign-in at [client]'s provider and answers the provider's page to send the browser to. */
    suspend fun start(userId: Uuid, client: OAuthClient): String {
        val session = instance.createNewSession()
        session.attributes[OWNER] = userId
        session.attributes[PROVIDER] = client.provider
        session.nextStep()

        val step = session.authenticationSteps.last().second as OIDCPluginState
        return step.url.toString()
    }

    /**
     * The mailbox the flow [id] came back with, if it did and it is [userId]'s. A flow somebody else
     * started, one that ran out or is still at the provider, a login flow and a made-up id are all
     * the same miss.
     */
    fun get(id: String, userId: Uuid): OAuthMailbox? {
        val session = sessions[id]?.takeUnless { it.isExpired() } ?: return null
        if (session.attributes[OWNER] != userId) return null
        return session.identifiedUser?.user as? OAuthMailbox
    }
}

/**
 * Installs the sign-in flow with a step for each provider [OAuthProviders] has a client for, and
 * makes it available as [OAuthOnboardings].
 */
fun Application.installOAuthOnboardings() {
    val providers: OAuthProviders by dependencies

    val signIns = providers.clients.associate { client -> client.provider to signInStep(client) }
    val folders = FoldersStep()

    val instance = installAuthentikt {
        apiPrefix = INBOX_SIGN_IN_API_PREFIX
        baseUrl = providers.baseUrl
        // Where the provider's sign-in ends: the email account settings, which open "new inbox" on
        // the `_authentikt_session_id` authentikt adds.
        uiLoginBaseUrl = URLBuilder(providers.baseUrl).apply {
            parameters.append("settings", "email-accounts")
        }.buildString()
        // The time a user gets to pick the folders once back from the provider.
        sessionTimeout = 30.minutes

        signIns.values.forEach(::install)
        install(folders)

        authorization { session ->
            val signIn = signIns.getValue(session.attributes[PROVIDER]!!)
            if (session.has(signIn)) folders else signIn
        }
    }

    dependencies {
        provide<OAuthOnboardings> { OAuthOnboardings(instance) }
    }
}

private fun signInStep(client: OAuthClient) = OIDCPlugin<OAuthMailbox> {
    applicationName = client.provider.id
    clientId = client.config.clientId
    clientSecret = client.config.clientSecret
    authorizationEndpoint = client.endpoints.authorize
    tokenEndpoint = client.endpoints.token
    jwksUri = client.endpoints.jwks
    issuer = client.endpoints.issuer
    scopes(*client.provider.scopes.toTypedArray())
    client.provider.authorizeParameters.forEach { (name, value) -> authorizationParameter(name, value) }

    // No user info endpoint: Microsoft's access token is Outlook's, and Graph's endpoint does not
    // take it. The id token names the mailbox just as well, and authentikt has verified it.
    onUserInfo { _, _ ->
        // `email`, or for a Microsoft work account without one the `preferred_username`, which is
        // its sign-in address.
        val address = listOf("email", "preferred_username").firstNotNullOfOrNull { claim ->
            claims?.get(claim)?.jsonPrimitive?.contentOrNull?.takeIf { '@' in it }
        } ?: return@onUserInfo UserInfo.Result.Failure("${client.provider.id} did not name the mailbox that was signed in to")

        UserInfo.Result.Success(MailboxUser(OAuthMailbox(client.provider, address, tokens)))
    }
}

private class MailboxUser(mailbox: OAuthMailbox) : AuthentiktUser<OAuthMailbox>(mailbox) {
    override suspend fun getEmail() = user.address
    override suspend fun getUsername() = user.address
    override suspend fun getDisplayName() = user.address
}

/**
 * Where a flow waits once the provider named the mailbox. Never completes: the folders are picked
 * through the dialog's own routes, which find the mailbox through [OAuthOnboardings.get].
 */
private class FoldersStep : BasePlugin<OAuthMailbox, FoldersStep.State>("overmail/inbox-onboarding/folders") {
    object State : BaseState {
        override suspend fun isCompleted() = false
        override suspend fun createClientState(session: Session<*>): Map<String, Any?> = emptyMap()
    }

    override suspend fun createState(session: Session<*>) = State
    override fun installRoutes(inRoute: Route, authentiktInstance: AuthentiktInstance<OAuthMailbox>) {}
}
