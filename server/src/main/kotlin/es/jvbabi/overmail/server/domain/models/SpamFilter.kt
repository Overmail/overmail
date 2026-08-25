package es.jvbabi.overmail.server.domain.models

import es.jvbabi.overmail.server.domain.spam.SpamRule
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * One spam filter of [user]: the rule, what they called it, and whether it is switched on.
 *
 * The rule is the tree itself rather than the JSON it is stored as -- whoever holds a filter
 * against a mail should not have to parse it first, see
 * [es.jvbabi.overmail.server.domain.spam.SpamRuleMatcher].
 */
data class SpamFilter(
    val id: Uuid,
    val user: User,
    val name: String,
    val rule: SpamRule,
    /** Whether the filter files new mail. A filter that is off keeps what it has already caught. */
    val isActive: Boolean,
    val createdAt: Instant,
)
