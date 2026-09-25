package es.jvbabi.overmail.server.http.senders.search

import es.jvbabi.overmail.server.database.models.*
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.intQueryParameter
import es.jvbabi.overmail.server.http.api.queryParameter
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import es.jvbabi.overmail.server.http.avatar.avatarPadding
import es.jvbabi.overmail.server.http.avatar.avatarUrlOrNull
import es.jvbabi.overmail.server.util.fuzzyContains
import io.ktor.openapi.JsonSchema
import io.ktor.server.auth.authenticate
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Count
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.jdbc.select

/** What a search answers when the caller does not say how many. */
private const val DEFAULT_LIMIT = 10

/**
 * One display name a sender used, with how often and how recently. The same address can appear
 * under several names ("J. Babies" vs. "Julius Babies"), so the rows get folded per sender.
 */
private data class NameVariant(val name: String?, val emailCount: Long, val lastSent: Instant?)

/**
 * What every row of one sender carries the same: the address is on the address book entry and the
 * picture hangs off that, so folding the name variants can just take it from the first row.
 */
private data class SenderAvatar(val address: String, val url: String?, val padding: Double?)

/**
 * The user's correspondents, most written to them first: `GET /api/senders/search?query=anna&limit=30`.
 * `limit` defaults to 10.
 */
fun Route.senderSearch() {
    authenticate {
        /**
         * Search the correspondents of the current user.
         *
         * Description: Fuzzy on the address and the name, the ones with the most mails first.
         *
         * Tag: Senders
         *
         * Query parameters:
         *   - query [String] What the address or name has to contain
         *   - limit [Int] How many senders to answer; defaults to 10
         *
         * Responses:
         *   - 200 [SenderSearchResponse] The senders
         */
        get {
            val userId = call.requireAuthenticatedUserId()
            val query = call.queryParameter("query").orEmpty()
            val limit = call.intQueryParameter("limit", default = DEFAULT_LIMIT, range = 1..Int.MAX_VALUE)

            val senders = call.database().query {
                val count = Count(Emails.id).alias("email_count")
                val lastSent = Emails.sent.max().alias("last_sent")

                // Grouped by name as well, so the variants can be weighed against each other
                // below; the sender's own row totals are summed back up in Kotlin.
                Emails
                    .innerJoin(EmailUsers)
                    .innerJoin(ImapAccounts)
                    .leftJoin(EmailAvatars)
                    .select(
                        EmailUsers.id,
                        EmailUsers.address,
                        EmailUsers.avatar,
                        EmailAvatars.circlePadding,
                        Emails.senderName,
                        count,
                        lastSent,
                    )
                    .where { ImapAccounts.user eq userId }
                    .groupBy(
                        EmailUsers.id,
                        EmailUsers.address,
                        EmailUsers.avatar,
                        EmailAvatars.circlePadding,
                        Emails.senderName,
                    )
                    .map { row ->
                        Triple(
                            row[EmailUsers.id].value,
                            SenderAvatar(row[EmailUsers.address], row.avatarUrlOrNull(), row.avatarPadding()),
                            NameVariant(row[Emails.senderName], row[count], row[lastSent]),
                        )
                    }
                    .groupBy { (id, _, _) -> id }
                    .map { (id, rows) ->
                        val (address, avatarUrl, avatarPadding) = rows.first().second
                        val variants = rows.map { (_, _, variant) -> variant }
                        SenderSearchResponse.Sender(
                            id = id,
                            // The name the sender uses most; the most recent one breaks a tie.
                            name = variants
                                .filter { it.name != null }
                                .maxWithOrNull(compareBy({ it.emailCount }, { it.lastSent }))
                                ?.name,
                            address = address,
                            avatarUrl = avatarUrl,
                            avatarPadding = avatarPadding,
                            emailCount = variants.sumOf { it.emailCount },
                        )
                    }
                    .sortedByDescending { it.emailCount }
            }

            val filtered = (if (query.isBlank()) senders
            else senders.filter { sender ->
                sender.address.lowercase() fuzzyContains query.lowercase() ||
                        sender.name?.lowercase()?.fuzzyContains(query.lowercase()) == true
            }).take(limit)

            call.respond(SenderSearchResponse(filtered))
        }
    }
}

@Serializable
private data class SenderSearchResponse(
    val senders: List<Sender>,
) {
    @Serializable
    data class Sender(
        @SerialName("id") val id: Uuid,
        @JsonSchema.Description("The display name they use most; null when they only ever sent a bare address")
        @SerialName("name") val name: String?,
        @SerialName("address") val address: String,
        @JsonSchema.Description("Where the picture of the sender is; null when there is none")
        @SerialName("avatar_url") val avatarUrl: String?,
        @JsonSchema.Description("How much of its box the picture gives up on every side to fit a circle, as a fraction; null when it needs none")
        @SerialName("avatar_padding") val avatarPadding: Double?,
        @JsonSchema.Description("How many mails of theirs the user has")
        @SerialName("email_count") val emailCount: Long,
    )
}
