package es.jvbabi.overmail.server.database.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.uuid.Uuid

/**
 * What a sign-in at a mail provider left behind: the tokens imap logs in with instead of a password.
 *
 * A grant starts out as an onboarding -- written the moment the provider sends the browser back,
 * so the "new inbox" dialog survives a reload and the server a restart -- and becomes an inbox's
 * login once the dialog is submitted. One that never gets there is dropped after a while, see
 * `OAuthTokens`. An inbox's grant keeps the id of the last sign-in that renewed it, so the dialog
 * that sign-in returns to can tell that it did.
 */
object OAuthGrants : UuidTable("oauth_grants") {
    val user = reference("user_id", Users, onDelete = ReferenceOption.CASCADE)

    /** The inbox that logs in with this grant; null while the dialog is still picking its folders. */
    val imapAccount = optReference("imap_account_id", ImapAccounts, onDelete = ReferenceOption.CASCADE).uniqueIndex()

    /**
     * The sign-in flow the grant came out of, which is what the browser carries back as
     * `_authentikt_session_id`. Cleared once the grant belongs to an inbox, and set again by a sign-in
     * that re-authenticates it.
     */
    val onboardingId = varchar("onboarding_id", 128).nullable().uniqueIndex()

    /** The id of the `OAuthProvider`. */
    val provider = varchar("provider", 32)

    /** The address that was signed in to, which is what imap logs in as. */
    val address = varchar("address", 255)

    val accessToken = text("access_token")
    val accessTokenExpiresAt = timestamp("access_token_expires_at")

    /** What gets the next [accessToken]. Without one the grant ends with its access token. */
    val refreshToken = text("refresh_token").nullable()

    /** When [accessToken] was issued; the renewal is timed from here. */
    val renewedAt = timestamp("renewed_at").defaultExpression(CurrentTimestamp)

    /** When a renewal last failed, which holds the next attempt back for a while. Null after a success. */
    val renewalFailedAt = timestamp("renewal_failed_at").nullable()

    /**
     * Whether the provider refused the grant -- revoked, expired or no longer allowed. A locked grant
     * is not renewed and its inbox not imported until the user signs in there again, which puts
     * fresh tokens on this row instead of creating a second inbox.
     */
    val requiresReauthentication = bool("requires_reauthentication").default(false)

    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
}

class OAuthGrant(id: EntityID<Uuid>) : UuidEntity(id) {
    companion object : UuidEntityClass<OAuthGrant>(OAuthGrants)

    var user by User referencedOn OAuthGrants.user
    var imapAccount by ImapAccount optionalReferencedOn OAuthGrants.imapAccount
    var onboardingId by OAuthGrants.onboardingId
    var provider by OAuthGrants.provider
    var address by OAuthGrants.address
    var accessToken by OAuthGrants.accessToken
    var accessTokenExpiresAt by OAuthGrants.accessTokenExpiresAt
    var refreshToken by OAuthGrants.refreshToken
    var renewedAt by OAuthGrants.renewedAt
    var renewalFailedAt by OAuthGrants.renewalFailedAt
    var requiresReauthentication by OAuthGrants.requiresReauthentication
    var createdAt by OAuthGrants.createdAt
}
