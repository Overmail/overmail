package es.jvbabi.overmail.server.oauth

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Sign-ins that came back with tokens, waiting for "new inbox" to be finished with them.
 *
 * The callback cannot create the inbox itself: which folders to keep is still to be chosen, in the
 * dialog the browser is sent back to. So the tokens wait here, under an id the dialog carries in
 * its url, and never leave the server. The lifetime is the time a user gets to pick the folders.
 */
class OAuthOnboardingStore(lifetime: Duration = 30.minutes) {

    /** A mailbox somebody signed in to, ready to be read. */
    data class Onboarding(
        val userId: Uuid,
        val provider: OAuthProvider,
        /** The address the provider named; what imap logs in as. */
        val address: String,
        val tokens: OAuthTokens,
    )

    private val entries = ExpiringMap<Onboarding>(lifetime)

    /** Keeps [onboarding] and answers the id the dialog continues it under. */
    fun create(onboarding: Onboarding): String = entries.put(onboarding)

    /** The onboarding under [id], if it is [userId]'s. Somebody else's is as unknown as a made-up id. */
    fun get(id: String, userId: Uuid): Onboarding? = entries.get(id)?.takeIf { it.userId == userId }
}
