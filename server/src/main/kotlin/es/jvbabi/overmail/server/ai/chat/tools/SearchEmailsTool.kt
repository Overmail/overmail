package es.jvbabi.overmail.server.ai.chat.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.EmailLabels
import es.jvbabi.overmail.server.database.models.EmailUsers
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.database.models.Labels
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.util.fuzzyContains
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** What one call may answer with. More than this is a question that needs narrowing, not paging. */
private const val MAX_RESULTS = 15

/**
 * How many rows the fuzzy matching may look at. Beyond this the mailbox is cut at the newest
 * mails: a fuzzy match cannot be pushed into sql, so every candidate is read into memory.
 */
private const val MAX_CANDIDATES = 2_000

/**
 * Finds mails by sender, subject and a few exact parameters, and answers with their metadata --
 * never their content. Reading one is [ReadEmailTool]'s job, which the agent can call for any id
 * from here.
 *
 * Bound to a single user like every chat tool: the mailbox it searches is the one of [userId],
 * and there is no argument that could point it at another.
 */
class SearchEmailsTool(
    private val userId: User.Id,
    private val database: OvermailDatabase,
    /** Called with the markup for the search that ran, so the answer shows what was looked for. */
    private val onSearch: (String) -> Unit = {},
) : Tool<SearchEmailsTool.Args, SearchEmailsTool.Result>(
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    name = NAME,
    description = "Search the user's emails and get their metadata: id, subject, sender, send " +
        "time, read state and labels. Every argument is optional and they are combined with AND; " +
        "with none the newest emails come back. Subject and sender are matched loosely, so a " +
        "part of a word or an address is enough. Read one of the results with the " +
        "`${ReadEmailTool.NAME}` tool when its content matters.",
) {

    @Serializable
    data class Args(
        @property:LLMDescription("Part of the subject, e.g. `invoice`. Matched loosely.")
        @SerialName("subject") val subject: String? = null,

        @property:LLMDescription(
            "Part of the sender's name or address, e.g. `github` or `@uni-potsdam.de`. Matched loosely."
        )
        @SerialName("sender") val sender: String? = null,

        @property:LLMDescription("Name of a label the email must carry, e.g. `Studium`.")
        @SerialName("label") val label: String? = null,

        @property:LLMDescription("`true` for read emails only, `false` for unread ones.")
        @SerialName("is_read") val isRead: Boolean? = null,

        @property:LLMDescription("Only emails sent at or after this date, as `2026-09-03`.")
        @SerialName("sent_after") val sentAfter: String? = null,

        @property:LLMDescription("Only emails sent at or before this date, as `2026-09-03`.")
        @SerialName("sent_before") val sentBefore: String? = null,

        @property:LLMDescription("How many emails to return at most, 1 to $MAX_RESULTS.")
        @SerialName("limit") val limit: Int? = null,
    )

    @Serializable
    sealed class Result {

        @Serializable
        @SerialName("emails")
        data class Emails(
            @SerialName("emails") val emails: List<Email>,
            /** True when the search hit its limit, so narrowing it down would show more. */
            @SerialName("more_results") val moreResults: Boolean,
        ) : Result()

        /** A date argument that is not a date; the model gets to fix it and call again. */
        @Serializable
        @SerialName("invalid_argument")
        data class InvalidArgument(
            @SerialName("argument") val argument: String,
            @SerialName("message") val message: String,
        ) : Result()

        @Serializable
        data class Email(
            @SerialName("id") val id: String,
            @SerialName("subject") val subject: String,
            /** The sender as an entity, so answers can point at the person rather than a string. */
            @SerialName("sender_id") val senderId: String,
            @SerialName("sender_address") val senderAddress: String,
            @SerialName("sender_name") val senderName: String?,
            @SerialName("sent") val sent: String,
            @SerialName("is_read") val isRead: Boolean,
            @SerialName("labels") val labels: List<Label>,
        )

        @Serializable
        data class Label(
            @SerialName("id") val id: String,
            @SerialName("name") val name: String,
        )
    }

    override suspend fun execute(args: Args): Result {
        val sentAfter = args.sentAfter
            ?.let { parseDate(it, endOfDay = false) ?: return Result.InvalidArgument("sent_after", DATE_HINT) }
        val sentBefore = args.sentBefore
            ?.let { parseDate(it, endOfDay = true) ?: return Result.InvalidArgument("sent_before", DATE_HINT) }
        val limit = args.limit?.coerceIn(1, MAX_RESULTS) ?: MAX_RESULTS

        // Emitted for the search that ran, not for its outcome: the user asked what was looked
        // for, and "nothing found" is an answer to that too.
        onSearch(markup(subject = args.subject, sender = args.sender))

        return database.query {
            // Everything that can be a condition in sql is one; only the loose matching of
            // subject and sender happens below, on the rows this leaves.
            val query = Emails
                .join(ImapAccounts, JoinType.INNER, Emails.imapAccount, ImapAccounts.id)
                .join(EmailUsers, JoinType.INNER, Emails.sender, EmailUsers.id)
                .select(
                    Emails.id,
                    Emails.subject,
                    Emails.senderName,
                    Emails.sent,
                    Emails.isRead,
                    EmailUsers.id,
                    EmailUsers.address,
                )
                .where { ImapAccounts.user eq userId }

            args.isRead?.let { isRead -> query.andWhere { Emails.isRead eq isRead } }
            sentAfter?.let { after -> query.andWhere { Emails.sent greaterEq after } }
            sentBefore?.let { before -> query.andWhere { Emails.sent lessEq before } }

            args.label?.let { label ->
                val labelIds = Labels
                    .select(Labels.id, Labels.name)
                    .where { Labels.owner eq userId }
                    .filter { row -> row[Labels.name] fuzzyContains label }
                    .map { row -> row[Labels.id] }

                // No label of the user matches, so nothing can carry it.
                if (labelIds.isEmpty()) return@query Result.Emails(emails = emptyList(), moreResults = false)

                query.andWhere {
                    Emails.id inList EmailLabels
                        .select(EmailLabels.email)
                        .where { EmailLabels.label inList labelIds }
                        .map { row -> row[EmailLabels.email] }
                }
            }

            val candidates = query
                .orderBy(Emails.sent, SortOrder.DESC)
                .limit(MAX_CANDIDATES)
                .filter { row ->
                    val subjectMatches = args.subject.isNullOrBlank()
                        || row[Emails.subject] fuzzyContains args.subject
                    // Name and address are one field to the model: it cannot know which of the
                    // two carries what it is looking for.
                    val senderMatches = args.sender.isNullOrBlank()
                        || row[EmailUsers.address] fuzzyContains args.sender
                        || row[Emails.senderName]?.fuzzyContains(args.sender) == true
                    subjectMatches && senderMatches
                }

            val page = candidates.take(limit)
            val labels = labelsOf(page.map { row -> row[Emails.id].value })

            Result.Emails(
                emails = page.map { row ->
                    val id = row[Emails.id].value
                    Result.Email(
                        id = id.toString(),
                        subject = row[Emails.subject],
                        senderId = row[EmailUsers.id].value.toString(),
                        senderAddress = row[EmailUsers.address],
                        senderName = row[Emails.senderName],
                        sent = row[Emails.sent].toString(),
                        isRead = row[Emails.isRead],
                        labels = labels[id].orEmpty(),
                    )
                },
                moreResults = candidates.size > page.size,
            )
        }
    }

    private fun labelsOf(emailIds: List<Uuid>): Map<Uuid, List<Result.Label>> {
        if (emailIds.isEmpty()) return emptyMap()

        return EmailLabels
            .join(Labels, JoinType.INNER, EmailLabels.label, Labels.id)
            .select(EmailLabels.email, Labels.id, Labels.name)
            .where { EmailLabels.email inList emailIds }
            .groupBy(
                { row -> row[EmailLabels.email].value },
                { row -> Result.Label(id = row[Labels.id].value.toString(), name = row[Labels.name]) },
            )
    }

    companion object {
        const val NAME = "search_emails"

        private const val DATE_HINT = "Expected a date as YYYY-MM-DD."

        /**
         * A bare date covers the whole day: `sent_after` starts at midnight, `sent_before` ends
         * just before the next one, so a search for a single day finds what arrived during it.
         * A full timestamp is taken as it stands.
         */
        internal fun parseDate(value: String, endOfDay: Boolean): Instant? {
            val trimmed = value.trim()
            val text = if (DATE_ONLY.matches(trimmed)) {
                trimmed + if (endOfDay) "T23:59:59.999999999Z" else "T00:00:00Z"
            } else {
                trimmed
            }
            return runCatching { Instant.parse(text) }.getOrNull()
        }

        private val DATE_ONLY = Regex("""\d{4}-\d{2}-\d{2}""")

        /**
         * The element the chat renders for a search the agent ran. Only what was searched for --
         * an argument that was not given stays empty and is left out by the client.
         */
        fun markup(subject: String?, sender: String?): String =
            """<toolcall-search-emails subject="${escapeAttribute(subject.orEmpty())}" sender="${escapeAttribute(sender.orEmpty())}"></toolcall-search-emails>"""
    }
}
