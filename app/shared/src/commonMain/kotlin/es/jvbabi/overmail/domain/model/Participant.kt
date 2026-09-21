package es.jvbabi.overmail.domain.model

import kotlin.uuid.Uuid

/** Somebody the account corresponds with; what a sender or recipient filter is on. */
data class Participant(
    val id: Uuid,
    /** The name they write under most, null when they only ever sent a bare address. */
    val name: String?,
    val email: String,
    /** Path of their picture on the account's homeserver. */
    val avatarUrl: String?,
    /** How much of its box the picture gives up to fit a circle; null for one that can be clipped. */
    val avatarPadding: Double?,
    val emailCount: Long,
    val overmailAccount: OvermailAccount,
) {
    /** What they are called where there is room for one thing: the name, else the address. */
    val displayName: String get() = name ?: email
}
