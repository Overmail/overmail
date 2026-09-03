package es.jvbabi.overmail.server.http.senders.search

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.*
import es.jvbabi.overmail.server.http.avatar.avatarPadding
import es.jvbabi.overmail.server.http.avatar.avatarUrlOrNull
import es.jvbabi.overmail.server.util.fuzzyContains
import io.ktor.server.auth.*
import io.ktor.server.plugins.di.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Count
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val MAX_RESULTS = 10

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

fun Route.senderSearch() {
    authenticate {
        get {
            val db = application.dependencies.resolve<OvermailDatabase>()

            val user = call.principal<User>()!!
            val query = call.request.queryParameters["query"]?.trim() ?: ""

            val senders = db.query {
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
                    .where { ImapAccounts.user eq user.id.value }
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
            }).take(MAX_RESULTS)

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
        /** Display name from the sender's mails, absent when they only ever sent a bare address. */
        @SerialName("name") val name: String?,
        @SerialName("address") val address: String,
        @SerialName("avatar_url") val avatarUrl: String?,
        /** Whether that picture may be clipped to a circle, see `EmailAvatars.circlePadding`. */
        @SerialName("avatar_padding") val avatarPadding: Double?,
        @SerialName("email_count") val emailCount: Long,
    )
}
