package es.jvbabi.overmail.server.ai.chat.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.EmailRecipientType
import es.jvbabi.overmail.server.database.models.EmailRecipients
import es.jvbabi.overmail.server.database.models.EmailUsers
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.http.avatar.avatarUrl
import es.jvbabi.overmail.server.util.HtmlToText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

/** Long enough for a normal mail, short enough to leave room for the rest of the conversation. */
private const val MAX_BODY_CHARS = 8_000

/**
 * Reads one mail by id.
 *
 * Bound to a single user: [userId] is the owner of the chat this tool was built for, and the
 * lookup only matches mails imported through one of their accounts. A mail of somebody else is
 * reported as unknown, exactly like an id that does not exist -- the model must not be able to
 * tell the two apart, and there is no code path here that could return a foreign mail.
 */
class ReadEmailTool(
    private val userId: User.Id,
    private val database: OvermailDatabase,
    /**
     * Called with the markup for a mail that was read, so the answer can show which one the agent
     * looked at. Not called when there was nothing to read.
     */
    private val onEmailRead: (String) -> Unit = {},
) : Tool<ReadEmailTool.Args, ReadEmailTool.Result>(
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    name = NAME,
    description = "Read one of the user's emails by its id, including sender, recipients, " +
        "subject, send time and body text. Ids look like `[email:<id>]` in the user's message.",
) {

    @Serializable
    data class Args(
        @property:LLMDescription("The id of the email to read, without the `[email:` wrapper.")
        @SerialName("email_id") val emailId: String,
    )

    @Serializable
    sealed class Result {

        @Serializable
        @SerialName("email")
        data class Email(
            @SerialName("id") val id: String,
            @SerialName("subject") val subject: String,
            @SerialName("sender_address") val senderAddress: String,
            /** Display name from this mail's header, absent for a bare address. */
            @SerialName("sender_name") val senderName: String?,
            @SerialName("sent") val sent: String,
            @SerialName("is_read") val isRead: Boolean,
            @SerialName("recipients") val recipients: List<Recipient>,
            /** Plain text of the mail, null when it carries no readable body at all. */
            @SerialName("body") val body: String?,
            /** True when [body] was cut off at the end, so the model knows it is not the whole mail. */
            @SerialName("body_truncated") val bodyTruncated: Boolean,
        ) : Result()

        /** Unknown id, malformed id, or a mail belonging to somebody else -- all the same here. */
        @Serializable
        @SerialName("not_found")
        data class NotFound(
            @SerialName("message") val message: String = "No email with this id belongs to the user.",
        ) : Result()
    }

    @Serializable
    data class Recipient(
        @SerialName("address") val address: String,
        @SerialName("name") val name: String?,
        @SerialName("type") val type: EmailRecipientType,
    )

    override suspend fun execute(args: Args): Result {
        val emailId = Uuid.parseOrNull(args.emailId.trim()) ?: return Result.NotFound()

        return database.query {
            // Columns through the DSL rather than the DAO entity: loading an Email reads its raw
            // source with it, which is the whole mail and can be megabytes.
            val row = Emails
                .join(ImapAccounts, JoinType.INNER, Emails.imapAccount, ImapAccounts.id)
                .join(EmailUsers, JoinType.INNER, Emails.sender, EmailUsers.id)
                .select(
                    Emails.subject,
                    Emails.sent,
                    Emails.senderName,
                    Emails.textContent,
                    Emails.htmlContent,
                    Emails.isRead,
                    EmailUsers.address,
                    EmailUsers.avatar,
                )
                // The ownership check is part of the lookup, not a test on the result: there is no
                // moment where a foreign mail has been read.
                .where { (Emails.id eq emailId) and (ImapAccounts.user eq userId) }
                .singleOrNull()
                ?: return@query Result.NotFound()

            val recipients = EmailRecipients
                .join(EmailUsers, JoinType.INNER, EmailRecipients.emailUser, EmailUsers.id)
                .select(EmailUsers.address, EmailRecipients.name, EmailRecipients.type)
                .where { EmailRecipients.email eq emailId }
                .map { recipient ->
                    Recipient(
                        address = recipient[EmailUsers.address],
                        name = recipient[EmailRecipients.name],
                        type = recipient[EmailRecipients.type],
                    )
                }

            // Some mails ship no text/plain part; without the fallback the model would be told
            // the mail is empty while its content sits in the HTML part.
            val body = row[Emails.textContent]
                ?: row[Emails.htmlContent]?.let { html -> HtmlToText.convert(html) }

            onEmailRead(
                markup(
                    emailId = emailId,
                    subject = row[Emails.subject],
                    avatarUrl = row[EmailUsers.avatar]?.value?.let(::avatarUrl),
                )
            )

            Result.Email(
                id = emailId.toString(),
                subject = row[Emails.subject],
                senderAddress = row[EmailUsers.address],
                senderName = row[Emails.senderName],
                sent = row[Emails.sent].toString(),
                isRead = row[Emails.isRead],
                recipients = recipients,
                body = body?.take(MAX_BODY_CHARS),
                bodyTruncated = (body?.length ?: 0) > MAX_BODY_CHARS,
            )
        }
    }

    companion object {
        const val NAME = "read_email"

        /**
         * The element the chat renders for a mail the agent read. Written into the answer itself,
         * so it survives a reload like the rest of the message.
         */
        fun markup(emailId: Uuid, subject: String, avatarUrl: String?): String =
            """<toolcall-read-email emailId="$emailId" avatarUrl="${escapeAttribute(avatarUrl.orEmpty())}" subject="${escapeAttribute(subject)}"></toolcall-read-email>"""

        /** A subject is arbitrary text and ends up inside an attribute, quotes and all. */
        private fun escapeAttribute(value: String): String = value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
}
