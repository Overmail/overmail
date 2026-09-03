package es.jvbabi.overmail.server.http.email.search

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.*
import es.jvbabi.overmail.server.http.avatar.avatarPadding
import es.jvbabi.overmail.server.http.avatar.avatarUrlOrNull
import es.jvbabi.overmail.server.util.FuzzyMatchResult
import es.jvbabi.overmail.server.util.detailedFuzzyContains
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.notExists
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val MAX_RESULTS = 10

/**
 * True for mails whose latest archive event is not Spam. The archive table is an event log, so
 * only the latest event decides (see `emailIsNotArchived` in `http/stack/stackSocket.kt`): a mail
 * counts as spam when it has a Spam event with no other event at or after it.
 */
private fun emailIsNotSpam(): Op<Boolean> {
    val laterNonSpam = EmailArchives.alias("later_non_spam")
    return notExists(
        EmailArchives.selectAll().where {
            (EmailArchives.email eq Emails.id) and
                    (EmailArchives.action eq EmailArchiveAction.Spam) and
                    notExists(
                        laterNonSpam.selectAll().where {
                            (laterNonSpam[EmailArchives.email] eq EmailArchives.email) and
                                    (laterNonSpam[EmailArchives.action] neq EmailArchiveAction.Spam) and
                                    (laterNonSpam[EmailArchives.createdAt] greaterEq EmailArchives.createdAt)
                        }
                    )
        }
    )
}

private data class Candidate(
    val id: Uuid,
    val subject: String,
    val senderName: String?,
    val senderAddress: String,
    val avatarUrl: String?,
    val avatarPadding: Double?,
    val sentAt: Instant,
)

private data class MatchedCandidate(
    val candidate: Candidate,
    val subjectRanges: List<IntRange>,
    val nameRanges: List<IntRange>,
    val addressRanges: List<IntRange>,
    /** Higher is a better match; results are ranked by this before falling back to send time. */
    val score: Double,
)

/** A hit in the subject or sender name says more about relevance than one in the raw address. */
private const val SUBJECT_WEIGHT = 1.0
private const val NAME_WEIGHT = 1.0
private const val ADDRESS_WEIGHT = 0.8

/** Merges overlapping or adjacent ranges so tokens that hit the same spot highlight it once. */
private fun List<IntRange>.merged(): List<IntRange> {
    val result = mutableListOf<IntRange>()
    for (range in sortedBy { it.first }) {
        val last = result.lastOrNull()
        if (last != null && range.first <= last.last + 1) {
            if (range.last > last.last) result[result.lastIndex] = last.first..range.last
        } else {
            result.add(range)
        }
    }
    return result
}

/**
 * How well [result] fits [text]: a contiguous hit at the start of a short text beats the same
 * characters scattered through a long one. Only relative size matters, not the absolute value.
 */
private fun fieldScore(text: String, result: FuzzyMatchResult): Double {
    if (!result.matches) return 0.0

    val matchedChars = result.ranges.sumOf { range -> range.last - range.first + 1 }
    // One range means the token was matched as a block; more ranges mean scattered characters.
    val contiguity = 1.0 / result.ranges.size
    val start = result.ranges.first().first
    val atWordStart = start == 0 || !text[start - 1].isLetterOrDigit()
    // The same hit in a shorter text is the more relevant one.
    val coverage = matchedChars.toDouble() / text.length.coerceAtLeast(1)

    return 1.0 + 2.0 * contiguity + (if (atWordStart) 1.0 else 0.0) + 1.0 / (1.0 + start) + coverage
}

/**
 * Matches every whitespace-separated token of [query] against subject, sender name and sender
 * address; the mail matches when each token hits at least one of them. A query can therefore mix
 * subject and sender parts ("julius rechnung"). Each token contributes its best field score, so a
 * mail that matches every token cleanly outranks one that only matches them by scattered letters.
 */
private fun Candidate.matchAgainst(query: String): MatchedCandidate? {
    val subjectRanges = mutableListOf<IntRange>()
    val nameRanges = mutableListOf<IntRange>()
    val addressRanges = mutableListOf<IntRange>()

    var score = 0.0

    for (token in query.split(Regex("\\s+")).filter { it.isNotBlank() }) {
        val subjectResult = subject detailedFuzzyContains token
        val nameResult = senderName?.detailedFuzzyContains(token) ?: FuzzyMatchResult.NO_MATCH
        val addressResult = senderAddress detailedFuzzyContains token
        if (!subjectResult.matches && !nameResult.matches && !addressResult.matches) return null
        subjectRanges += subjectResult.ranges
        nameRanges += nameResult.ranges
        addressRanges += addressResult.ranges

        score += maxOf(
            fieldScore(subject, subjectResult) * SUBJECT_WEIGHT,
            fieldScore(senderName.orEmpty(), nameResult) * NAME_WEIGHT,
            fieldScore(senderAddress, addressResult) * ADDRESS_WEIGHT,
        )
    }

    return MatchedCandidate(
        candidate = this,
        subjectRanges = subjectRanges.merged(),
        nameRanges = nameRanges.merged(),
        addressRanges = addressRanges.merged(),
        score = score,
    )
}

private fun matchable(text: String, ranges: List<IntRange>) = EmailSearchResponse.Email.MatchableString(
    text = text,
    matches = ranges.map { range ->
        // end is exclusive, ready for String.slice on the client.
        EmailSearchResponse.Email.MatchableString.Match(start = range.first, end = range.last + 1)
    },
)

fun Route.emailSearch() {
    authenticate {
        get {
            val db = application.dependencies.resolve<OvermailDatabase>()
            val user = call.principal<User>()!!
            val query = call.request.queryParameters["query"]?.trim() ?: ""

            val emails = db.query {
                // Selected column by column instead of through the Email entity: that one reads
                // raw_content with every row, which is the whole mail source.
                val candidates = Emails
                    .leftJoin(ImapAccounts)
                    .leftJoin(EmailUsers)
                    .leftJoin(EmailAvatars)
                    .select(
                        Emails.id,
                        Emails.subject,
                        Emails.senderName,
                        Emails.sent,
                        EmailUsers.address,
                        EmailUsers.avatar,
                        EmailAvatars.circlePadding,
                    )
                    .where { ImapAccounts.user eq user.id.value }
                    .andWhere { emailIsNotSpam() }
                    .orderBy(Emails.sent, SortOrder.DESC)
                    .let { if (query.isBlank()) it.limit(MAX_RESULTS) else it }
                    .map { row ->
                        Candidate(
                            id = row[Emails.id].value,
                            subject = row[Emails.subject],
                            senderName = row[Emails.senderName],
                            senderAddress = row[EmailUsers.address],
                            avatarUrl = row.avatarUrlOrNull(),
                            avatarPadding = row.avatarPadding(),
                            sentAt = row[Emails.sent],
                        )
                    }

                val matched =
                    if (query.isBlank()) candidates.map { MatchedCandidate(it, emptyList(), emptyList(), emptyList(), 0.0) }
                    else candidates
                        .mapNotNull { candidate -> candidate.matchAgainst(query) }
                        // Best match first; equally good matches stay newest first.
                        .sortedWith(
                            compareByDescending<MatchedCandidate> { it.score }
                                .thenByDescending { it.candidate.sentAt }
                        )
                        .take(MAX_RESULTS)

                val recipientsByEmail =
                    if (matched.isEmpty()) emptyMap()
                    else EmailRecipients
                        .leftJoin(EmailUsers)
                        .select(EmailRecipients.email, EmailRecipients.name, EmailUsers.address)
                        .where { EmailRecipients.email inList matched.map { it.candidate.id } }
                        .andWhere { EmailRecipients.type eq EmailRecipientType.RECIPIENT }
                        .map { row -> row[EmailRecipients.email].value to (row[EmailRecipients.name] ?: row[EmailUsers.address]) }
                        .groupBy({ (emailId, _) -> emailId }, { (_, recipient) -> recipient })

                matched.map { (candidate, subjectRanges, nameRanges, addressRanges, _) ->
                    EmailSearchResponse.Email(
                        id = candidate.id,
                        subject = matchable(candidate.subject, subjectRanges),
                        from = EmailSearchResponse.Email.From(
                            name = candidate.senderName?.let { matchable(it, nameRanges) },
                            address = matchable(candidate.senderAddress, addressRanges),
                        ),
                        avatarUrl = candidate.avatarUrl,
                        avatarPadding = candidate.avatarPadding,
                        to = recipientsByEmail[candidate.id].orEmpty(),
                        date = candidate.sentAt.toString(),
                    )
                }
            }

            call.respond(EmailSearchResponse(emails))
        }
    }
}

@Serializable
private data class EmailSearchResponse(
    @SerialName("emails") val emails: List<Email>
) {
    @Serializable
    data class Email(
        @SerialName("id") val id: Uuid,
        @SerialName("subject") val subject: MatchableString,
        @SerialName("from") val from: From,
        @SerialName("avatar_url") val avatarUrl: String?,
        /** Whether that picture may be clipped to a circle, see `EmailAvatars.circlePadding`. */
        @SerialName("avatar_padding") val avatarPadding: Double?,
        @SerialName("to") val to: List<String>,
        @SerialName("date") val date: String
    ) {
        @Serializable
        data class From(
            @SerialName("name") val name: MatchableString?,
            @SerialName("address") val address: MatchableString,
        )

        @Serializable
        data class MatchableString(
            @SerialName("text") val text: String,
            @SerialName("matches") val matches: List<Match>
        ) {
            @Serializable
            data class Match(
                @SerialName("start") val start: Int,
                @SerialName("end") val end: Int
            )
        }
    }
}
