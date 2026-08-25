package es.jvbabi.overmail.server.database.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.datetime.timestamp

/**
 * The spam filters a user wrote, one row each. A filter is a name, the rule behind it and whether
 * it is switched on; what it has caught is recorded per mail in [EmailSpam].
 */
object Filters : UuidTable("filters") {
    /** Whose filter this is. Filters are per user, not per account. */
    val user = reference("user_id", Users, onDelete = ReferenceOption.CASCADE)

    val name = varchar("name", 255)

    /**
     * The rule as the JSON the editor builds, see
     * [es.jvbabi.overmail.server.domain.spam.SpamRule]. Text rather than columns: a rule is a tree
     * of unbounded shape, and nothing queries into it -- it is read whole and held against a mail.
     */
    val rule = text("rule")

    /** Whether the filter files new mail. A filter that is off keeps what it has already caught. */
    val isActive = bool("is_active").default(true)

    val createdAt = timestamp("created_at")

    init {
        // A user's filters, which is the only way this table is read.
        index(false, user, createdAt)
    }
}
