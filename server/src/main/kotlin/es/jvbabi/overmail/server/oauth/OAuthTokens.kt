package es.jvbabi.overmail.server.oauth

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.OAuthGrant
import es.jvbabi.overmail.server.database.models.OAuthGrants
import es.jvbabi.overmail.server.database.models.User
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** How long before its access token runs out a grant is renewed. */
private val RENEW_BEFORE_EXPIRY = 1.hours

/** How often [OAuthTokens.run] looks for grants that are due. */
private val RENEWAL_INTERVAL = 1.minutes

/** How long a grant whose renewal failed is left alone before it is tried again. */
private val RENEWAL_RETRY_AFTER = 15.minutes

/** Below this an access token is renewed before it is handed out, rather than left to the job. */
private val HAND_OUT_MARGIN = 2.minutes

/** How long a sign-in waits for the dialog to be submitted before its grant is dropped. */
val ONBOARDING_LIFETIME = 1.days

/** Assumed where a provider does not say how long its access token lasts. */
private val DEFAULT_TOKEN_LIFETIME = 1.hours

/**
 * The tokens of every [OAuthGrant]: stores what a sign-in came back with, hands out a current
 * access token to whoever logs in over imap, and renews them before they run out.
 */
class OAuthTokens(
    private val database: OvermailDatabase,
    private val providers: OAuthProviders,
    private val http: HttpClient = HttpClient(CIO),
    private val clock: Clock = Clock.System,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** One renewal per grant at a time: the importer and the job may both find it due. */
    private val renewals = ConcurrentHashMap<Uuid, Mutex>()

    /**
     * Writes what the sign-in [onboardingId] of [userId] came back with.
     *
     * Usually an onboarding, for "new inbox" to finish. But where the mailbox is already an inbox of
     * the user's whose grant is locked, the sign-in is the re-authentication it was waiting for: the
     * tokens go onto that grant, it is unlocked, and its inbox is answered. An inbox whose grant is
     * fine is not touched -- that sign-in becomes an onboarding, and submitting it is the conflict
     * it always was.
     */
    suspend fun storeOnboarding(onboardingId: String, userId: Uuid, mailbox: OAuthMailbox): Uuid? {
        val tokens = mailbox.tokens
        val expiresAt = tokens.expiresAt ?: (tokens.receivedAt + DEFAULT_TOKEN_LIFETIME)
        return database.query {
            val locked = OAuthGrant.find {
                (OAuthGrants.user eq userId) and
                    (OAuthGrants.provider eq mailbox.provider.id) and
                    (OAuthGrants.address eq mailbox.address) and
                    OAuthGrants.imapAccount.isNotNull() and
                    (OAuthGrants.requiresReauthentication eq true)
            }.firstOrNull()

            if (locked != null) {
                locked.onboardingId = onboardingId
                locked.accessToken = tokens.accessToken
                locked.accessTokenExpiresAt = expiresAt
                // A provider that hands out no new one keeps the old one valid.
                tokens.refreshToken?.let { locked.refreshToken = it }
                locked.renewedAt = tokens.receivedAt
                locked.renewalFailedAt = null
                locked.requiresReauthentication = false
                return@query locked.readValues[OAuthGrants.imapAccount]?.value
            }

            OAuthGrant.new {
                this.user = User[userId]
                this.onboardingId = onboardingId
                this.provider = mailbox.provider.id
                this.address = mailbox.address
                this.accessToken = tokens.accessToken
                this.accessTokenExpiresAt = expiresAt
                this.refreshToken = tokens.refreshToken
                this.renewedAt = tokens.receivedAt
            }
            null
        }
    }

    /**
     * An access token of [grantId] that is good for a while yet, renewed first where it is about
     * to run out. Null for a grant that is gone. A grant that cannot be renewed hands out the
     * token it has; the provider's refusal of it is what imap then reports.
     *
     * A locked grant, or one whose renewal failed a moment ago, is not tried again here: every
     * connection asks, and the provider would get the same doomed request from each of them.
     */
    suspend fun accessToken(grantId: Uuid): String? {
        val grant = database.query { OAuthGrant.findById(grantId)?.snapshot() } ?: return null
        val now = clock.now()
        if (grant.expiresAt - now > HAND_OUT_MARGIN) return grant.accessToken
        if (grant.requiresReauthentication || grant.failedRecently(now)) return grant.accessToken
        return renew(grantId) ?: grant.accessToken
    }

    /**
     * Renews every grant that is due, and drops the onboardings nobody finished. Runs until cancelled.
     *
     * [onRenewed] is told the inbox of every grant that got a new access token. An importer holds
     * the token it was started with, so this is where it is restarted onto the new one.
     */
    suspend fun run(onRenewed: suspend (imapAccountId: Uuid) -> Unit = {}) {
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                renewDue(onRenewed)
                dropAbandonedOnboardings()
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                logger.error(e) { "Renewing the oauth grants failed, retrying in $RENEWAL_INTERVAL" }
            }
            delay(RENEWAL_INTERVAL)
        }
    }

    internal suspend fun renewDue(onRenewed: suspend (imapAccountId: Uuid) -> Unit = {}) {
        val now = clock.now()
        val due = database.query {
            OAuthGrant.find {
                OAuthGrants.refreshToken.isNotNull() and (OAuthGrants.requiresReauthentication eq false)
            }.map { it.snapshot() }
        }.filter { grant -> !grant.failedRecently(now) && now >= grant.renewalDueAt }
        due.forEach { grant ->
            renew(grant.id) ?: return@forEach
            val imapAccountId = grant.imapAccountId ?: return@forEach
            try {
                onRenewed(imapAccountId)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                // The token is renewed either way; the other grants must not wait for this inbox.
                logger.error(e) { "Restarting the importer of ${grant.address} after its renewal failed" }
            }
        }
    }

    internal suspend fun dropAbandonedOnboardings() {
        val cutoff = clock.now() - ONBOARDING_LIFETIME
        database.query {
            OAuthGrants.deleteWhere { (OAuthGrants.imapAccount.isNull()) and (OAuthGrants.createdAt less cutoff) }
        }
    }

    /** Trades the refresh token of [grantId] for a new access token. Null when that did not work. */
    private suspend fun renew(grantId: Uuid): String? = renewals.computeIfAbsent(grantId) { Mutex() }.withLock {
        val grant = database.query { OAuthGrant.findById(grantId)?.snapshot() } ?: return@withLock null
        if (grant.requiresReauthentication) return@withLock null
        // Somebody else renewed it while this one waited for the lock.
        if (clock.now() < grant.renewalDueAt) {
            return@withLock grant.accessToken
        }

        val refreshToken = grant.refreshToken ?: return@withLock null
        val client = providers.byId(grant.provider)
        if (client == null) {
            logger.warn { "Cannot renew oauth grant $grantId: no client for provider '${grant.provider}'" }
            return@withLock null
        }

        val requestedAt = clock.now()
        val result = runCatching { requestTokens(client, refreshToken) }
        val tokens = result.getOrElse { e ->
            // The provider answering with an error is a refusal it will repeat -- the grant was
            // revoked, ran out or is no longer allowed -- and only a new sign-in gets past it. Not
            // reaching it at all, or a 5xx, is worth another try later.
            val refused = e is OAuthRenewalException && e.status < 500
            logger.warn(e) {
                "Renewing the oauth grant $grantId of ${grant.address} at ${grant.provider} failed" +
                    if (refused) ", it needs a new sign-in" else ""
            }
            database.query {
                OAuthGrant.findById(grantId)?.apply {
                    renewalFailedAt = clock.now()
                    if (refused) requiresReauthentication = true
                }
            }
            return@withLock null
        }

        database.query {
            val row = OAuthGrant.findById(grantId) ?: return@query
            row.accessToken = tokens.accessToken
            row.accessTokenExpiresAt = requestedAt + (tokens.expiresIn?.seconds ?: DEFAULT_TOKEN_LIFETIME)
            // Microsoft hands out a new refresh token with every renewal, Google keeps the old one.
            tokens.refreshToken?.let { row.refreshToken = it }
            row.renewedAt = requestedAt
            row.renewalFailedAt = null
        }
        logger.debug { "Renewed the oauth grant $grantId of ${grant.address}" }
        tokens.accessToken
    }

    private suspend fun requestTokens(client: OAuthClient, refreshToken: String): RefreshResponse {
        val response = http.submitForm(
            url = client.endpoints.token,
            formParameters = parameters {
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
                append("client_id", client.config.clientId)
                append("client_secret", client.config.clientSecret)
                // Microsoft issues the token for the resource of the scopes asked for here; without
                // them it would not be Outlook's.
                append("scope", client.provider.scopes.joinToString(" "))
            },
        )
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val error = runCatching { json.decodeFromString<ErrorResponse>(body) }.getOrNull()
            throw OAuthRenewalException(response.status.value, error?.error ?: "http_${response.status.value}", error?.description)
        }
        return json.decodeFromString<RefreshResponse>(body)
    }

    private fun OAuthGrant.snapshot() = GrantSnapshot(
        id = id.value,
        imapAccountId = readValues[OAuthGrants.imapAccount]?.value,
        provider = provider,
        address = address,
        accessToken = accessToken,
        expiresAt = accessTokenExpiresAt,
        refreshToken = refreshToken,
        renewedAt = renewedAt,
        renewalFailedAt = renewalFailedAt,
        requiresReauthentication = requiresReauthentication,
    )

    private data class GrantSnapshot(
        val id: Uuid,
        /** The inbox that logs in with the grant; null while it is still an onboarding. */
        val imapAccountId: Uuid?,
        val provider: String,
        val address: String,
        val accessToken: String,
        val expiresAt: Instant,
        val refreshToken: String?,
        val renewedAt: Instant,
        val renewalFailedAt: Instant?,
        val requiresReauthentication: Boolean,
    ) {
        fun failedRecently(now: Instant) = renewalFailedAt?.let { now - it < RENEWAL_RETRY_AFTER } ?: false

        val lifetime: Duration get() = expiresAt - renewedAt

        /**
         * [RENEW_BEFORE_EXPIRY] before the token runs out -- but no earlier than halfway through its
         * life. Google and Microsoft hand out tokens for an hour, which would otherwise be due the
         * moment they are issued and renewed every minute.
         */
        val renewalDueAt: Instant get() = maxOf(expiresAt - RENEW_BEFORE_EXPIRY, renewedAt + lifetime / 2)
    }

    @Serializable
    private data class RefreshResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("expires_in") val expiresIn: Long? = null,
        @SerialName("refresh_token") val refreshToken: String? = null,
    )

    @Serializable
    private data class ErrorResponse(
        @SerialName("error") val error: String,
        @SerialName("error_description") val description: String? = null,
    )
}

/** The provider refused to renew a grant. `invalid_grant` means the user revoked it or it ran out. */
class OAuthRenewalException(val status: Int, val error: String, description: String?) :
    RuntimeException("Token renewal refused: $error${description?.let { " ($it)" }.orEmpty()}")
