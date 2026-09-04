package es.jvbabi.overmail.server.database.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * The first line of a mail's body, as a listing shows it next to the subject. See `mailPreview`,
 * which is what fills it.
 *
 * Its own table rather than a column on [Emails]: a mail row is read whenever anything is read
 * about a mail, and this is derived text nobody needs there. A mail without a row here is one
 * whose body has not been looked at yet -- which is what the preview queue works through -- while
 * a mail with nothing readable in it gets an empty row, so the two are told apart.
 *
 * No entity: the key is the mail, so there is no id of its own to hang one on, and every access
 * is either a join or an upsert.
 */
object EmailPreviews : Table("email_previews") {
    val email = reference("email_id", Emails, onDelete = ReferenceOption.CASCADE)

    /** Capped at 300, which is [es.jvbabi.overmail.server.util.MAIL_PREVIEW_LENGTH] plus its ellipsis. */
    val preview = varchar("preview", 300)

    override val primaryKey = PrimaryKey(email)
}
