package es.jvbabi.overmail.server.http.labels.search

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.*
import es.jvbabi.overmail.server.util.fuzzyContains
import io.ktor.server.auth.*
import io.ktor.server.plugins.di.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Count
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

fun Route.labelSearch() {
    authenticate {
        get {
            val db = application.dependencies.resolve<OvermailDatabase>()

            val user = call.principal<User>()!!
            val query = call.request.queryParameters["query"]?.trim() ?: ""

            val labels = db.query {
                val count = Count(EmailLabels.id).alias("email_count")
                Labels
                    .leftJoin(EmailLabels)
                    .leftJoin(Emails)
                    .leftJoin(ImapAccounts)
                    .select(Labels.columns + count)
                    .where { Labels.owner eq user.id.value }
                    .andWhere { ImapAccounts.user eq user.id.value }
                    .groupBy(Labels.id)
                    .orderBy(count, SortOrder.DESC)
                    .let {
                        if (query.isBlank()) it.limit(10) else it
                    }
                    .map { row ->
                        LabelSearchResponse.Label(
                            id = row[Labels.id].value,
                            name = row[Labels.name],
                            color = row[Labels.color],
                            emailCount = row[count]
                        )
                    }
            }

            val filteredLabels =
                if (query.isBlank()) labels
                else labels.filter { label -> label.name.lowercase() fuzzyContains query.lowercase() }

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
        @SerialName("email_count") val emailCount: Long,
    )
}