package es.jvbabi.overmail.server.database.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.datetime.timestamp

/**
 * Every time a mail was marked as spam or taken back out of it, one row per change. Unspamming a
 * mail appends a row with [isSpam] false rather than removing the one that flagged it: the table is
 * the record of what happened to the mail, and a row that can be deleted records nothing -- same as
 * [Archived].
 *
 * The current state is *not* read from here -- [Emails.isSpam] carries it, so a listing does not
 * have to reach for the newest row per mail on every query.
 */
object EmailSpam : UuidTable("email_spam") {
    val email = reference("email_id", Emails, onDelete = ReferenceOption.CASCADE)

    /** True for the change that flagged the mail, false for the one that took it back out. */
    val isSpam = bool("is_spam")

    /**
     * The filter that caught the mail, null when someone marked it by hand. A filter is never
     * deleted, but if one ever is, what it caught stays -- so this goes null rather than taking
     * the record with it.
     */
    val filter = reference("filter_id", Filters, onDelete = ReferenceOption.SET_NULL).nullable()

    val createdAt = timestamp("created_at")

    init {
        // The history of one mail, in order: that is the only way this table is read.
        index(false, email, createdAt)
    }
}
