package es.jvbabi.overmail.server.oauth

import es.jvbabi.authentikt.core.AuthentiktInstance
import es.jvbabi.authentikt.core.AuthentiktUser
import es.jvbabi.authentikt.core.installAuthentikt
import es.jvbabi.authentikt.core.session.Session
import es.jvbabi.authentikt.core.step.BaseState
import es.jvbabi.authentikt.core.step.plugins.BasePlugin
import es.jvbabi.authentikt.core.step.plugins.builtin.OIDCPlugin
import es.jvbabi.authentikt.core.step.plugins.builtin.OIDCPluginState
import es.jvbabi.authentikt.core.step.plugins.builtin.OIDCTokens
import es.jvbabi.authentikt.core.step.plugins.builtin.UserInfo
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.OAuthGrant
import es.jvbabi.overmail.server.database.models.OAuthGrants
import io.ktor.http.URLBuilder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import es.jvbabi.overmail.server.jobs.importer.ImporterManager
import io.ktor.server.application.Application
import kotlinx.coroutines.launch
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

/** A mailbox somebody signed in to at a provider, as the flow holds it until it is stored. */
data class OAuthMailbox(
    val provider: OAuthProvider,
    /** The address the provider named; what imap logs in as. */
    val address: String,
    val tokens: OIDCTokens,
)

/** A sign-in that came back and waits for "new inbox" to be submitted. */
data class OAuthOnboarding(
    /** The grant the inbox will log in with. */
    val grantId: Uuid,
    val provider: OAuthProvider,
    /** The address that was signed in to; what imap logs in as. */
    val address: String,
    /**
     * The inbox the sign-in re-authenticated, if it was one whose grant was locked: there is
     * nothing left to submit then, the inbox has its tokens back.
     */
    val reauthenticatedInboxId: Uuid? = null,
)

private val OWNER = AttributeKey<Uuid>("overmail.oauth-onboarding.owner")
private val PROVIDER = AttributeKey<OAuthProvider>("overmail.oauth-onboarding.provider")

/**
 * The sign-ins "new inbox" sends the browser off to, as a flow of their own next to the login.
 *
 * A flow is started by a signed-in user for one provider, and its first step is that provider's
 * [OIDCPlugin]: it sends the browser there, trades the code for tokens and checks the id token. The
 * mailbox it came back with is then written down as an [es.jvbabi.overmail.server.database.models.OAuthGrant]
 * by [FoldersStep], and waits there while the dialog the browser is sent back to picks the folders.
 * The tokens never leave the server; the browser carries the flow's id, and only its owner gets
 * anything for it.
 */
class OAuthOnboardings internal constructor(
    private val instance: AuthentiktInstance<OAuthMailbox>,
    private val database: OvermailDatabase,
) {

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
     * The sign-in [id] came back with, if it did and it is [userId]'s. One somebody else started, one
     * still at the provider, one that was submitted or abandoned, a login flow and a made-up id are
     * all the same miss.
     *
     * Read from the database rather than the flow, which only lives in this process's memory: the
     * dialog has to survive a reload of the page and the server a restart.
     *
     * A sign-in that re-authenticated an inbox is found too, on that inbox's grant, and says so.
     */
    suspend fun get(id: String, userId: Uuid): OAuthOnboarding? = database.query {
        OAuthGrant.find {
            (OAuthGrants.onboardingId eq id) and (OAuthGrants.user eq userId)
        }.firstOrNull()?.let { grant ->
            val provider = OAuthProvider.byId(grant.provider) ?: return@let null
            OAuthOnboarding(
                grantId = grant.id.value,
                provider = provider,
                address = grant.address,
                reauthenticatedInboxId = grant.readValues[OAuthGrants.imapAccount]?.value,
            )
        }
    }
}

/**
 * Installs the sign-in flow with a step for each provider [OAuthProviders] has a client for, and
 * makes it available as [OAuthOnboardings].
 */
fun Application.installOAuthOnboardings() {
    val providers: OAuthProviders by dependencies
    val tokens: OAuthTokens by dependencies
    val database: OvermailDatabase by dependencies

    val signIns = providers.clients.associate { client -> client.provider to signInStep(client) }
    val folders = FoldersStep(store = { session, mailbox ->
        val reauthenticated = tokens.storeOnboarding(session.sessionId, session.attributes[OWNER]!!, mailbox)
        // Its importer was stopped while the grant was locked; it can log in again now.
        if (reauthenticated != null) launch { dependencies.resolve<ImporterManager>().reboot(reauthenticated) }
    })

    val instance = installAuthentikt {
        apiPrefix = INBOX_SIGN_IN_API_PREFIX
        baseUrl = providers.baseUrl
        // Where the provider's sign-in ends: the email account settings, which open "new inbox" on
        // the `_authentikt_session_id` authentikt adds.
        uiLoginBaseUrl = URLBuilder(providers.baseUrl).apply {
            parameters.append("settings", "email-accounts")
        }.buildString()
        // The time a user gets at the provider's page. Once back, the sign-in is in the database.
        sessionTimeout = 30.minutes

        signIns.values.forEach(::install)
        install(folders)

        authorization { session ->
            val signIn = signIns.getValue(session.attributes[PROVIDER]!!)
            if (session.has(signIn)) folders else signIn
        }
    }

    dependencies {
        provide<OAuthOnboardings> { OAuthOnboardings(instance, database) }
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
 * Where a flow ends once the provider named the mailbox: it is stored as an onboarding the moment
 * the flow gets here -- or re-authenticates the inbox it already is, see [OAuthTokens.storeOnboarding]
 * -- and the flow is done with. Never completes -- the folders are picked through
 * the dialog's own routes, which find the onboarding through [OAuthOnboardings.get].
 */
private class FoldersStep(
    private val store: suspend (Session<*>, OAuthMailbox) -> Unit,
) : BasePlugin<OAuthMailbox, FoldersStep.State>("overmail/inbox-onboarding/folders") {
    object State : BaseState {
        override suspend fun isCompleted() = false
        override suspend fun createClientState(session: Session<*>): Map<String, Any?> = emptyMap()
    }

    override suspend fun createState(session: Session<*>): State {
        val mailbox = session.identifiedUser?.user as OAuthMailbox
        store(session, mailbox)
        return State
    }

    override fun installRoutes(inRoute: Route, authentiktInstance: AuthentiktInstance<OAuthMailbox>) {}
}
