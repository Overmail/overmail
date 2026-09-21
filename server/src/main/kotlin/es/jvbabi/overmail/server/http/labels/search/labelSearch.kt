package es.jvbabi.overmail.server.http.labels.search

import es.jvbabi.overmail.server.database.models.*
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.intQueryParameter
import es.jvbabi.overmail.server.http.api.queryParameter
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import es.jvbabi.overmail.server.util.fuzzyContains
import io.ktor.server.auth.authenticate
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlin.uuid.Uuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Count
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select

/**
 * The user's labels, most used first: `GET /api/labels/search?query=uni&limit=30`.
 *
 * Without a query only the first `limit` (default 10) are answered; with one every fuzzy match,
 * unless a `limit` is sent.
 */
fun Route.labelSearch() {
    authenticate {
        get {
            val userId = call.requireAuthenticatedUserId()
            val query = call.queryParameter("query").orEmpty()
            val limit = call.intQueryParameter(
                name = "limit",
                default = if (query.isBlank()) 10 else Int.MAX_VALUE,
                range = 1..Int.MAX_VALUE,
            )

            val labels = call.database().query {
                val count = Count(EmailLabels.id).alias("email_count")
                Labels
                    .leftJoin(EmailLabels)
                    .leftJoin(Emails)
                    .leftJoin(ImapAccounts)
                    .select(Labels.columns + count)
                    .where { Labels.owner eq userId }
                    .andWhere { ImapAccounts.user eq userId }
                    .groupBy(Labels.id)
                    .orderBy(count, SortOrder.DESC)
                    .let {
                        if (query.isBlank()) it.limit(limit) else it
                    }
                    .map { row ->
                        LabelSearchResponse.Label(
                            id = row[Labels.id].value,
                            name = row[Labels.name],
                            color = row[Labels.color],
                            description = row[Labels.description],
                            createdAt = row[Labels.createdAt].epochSeconds,
                            createdByAgent = row[Labels.createdByAgent],
                            emailCount = row[count]
                        )
                    }
            }

            val filteredLabels =
                if (query.isBlank()) labels
                else labels.filter { label -> label.name.lowercase() fuzzyContains query.lowercase() }.take(limit)

            call.respond(LabelSearchResponse(filteredLabels))
        }
    }
}

@Serializable
private data class LabelSearchResponse(
    val labels: List<Label>,
) {
    @Serializable
    data class Label(
        @SerialName("id") val id: Uuid,
        @SerialName("name") val name: String,
        @SerialName("color") val color: String,
        @SerialName("description") val description: String?,
        @SerialName("created_at") val createdAt: Long,
        @SerialName("created_by_agent") val createdByAgent: Boolean,
        @SerialName("email_count") val emailCount: Long,
    )
}