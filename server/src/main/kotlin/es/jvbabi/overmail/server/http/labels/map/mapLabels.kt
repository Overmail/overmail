package es.jvbabi.overmail.server.http.labels.map

import es.jvbabi.overmail.server.database.models.EmailLabels
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.Labels
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUser
import io.ktor.openapi.JsonSchema
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

/**
 * How the labels hang together: `GET /api/labels/map`.
 *
 * Every label on at least one mail, and per other label how many of its mails carry that one too
 * -- the edges of the graph the label map draws. Labels on no mail at all are not on the map.
 */
fun Route.mapLabels() {
    authenticate {
        /**
         * Get how the labels occur together.
         *
         * Description: Every label on at least one mail, with how many mails carry it and, per other label, how many of those carry that one too.
         *
         * Tag: Labels
         *
         * Responses:
         *   - 200 [LabelMap] The labels and their relations
         */
        get {
            val db = call.database()
            val user = call.requireAuthenticatedUser()

            db.query {
                val emailCount = Count(EmailLabels.email, distinct = true)
                val allLabelIds = EmailLabels
                    .leftJoin(Labels)
                    .select(emailCount, EmailLabels.label, Labels.color, Labels.name)
                    .where { Labels.owner eq user.id }
                    .groupBy(EmailLabels.label, Labels.color, Labels.name)
                    .map { object  {
                        val labelId = it[EmailLabels.label].value
                        val emailCount = it[emailCount]
                        val color = it[Labels.color]
                        val name = it[Labels.name]
                    } }

                LabelMap(
                    allLabelIds.map { label ->

                        val emailIdsWithThisLabel = EmailLabels
                            .select(EmailLabels.email)
                            .where { EmailLabels.label eq label.labelId }
                            .map { it[EmailLabels.email].value }
                            .toSet()

                        val otherLabels = EmailLabels
                            .select(Count(EmailLabels.email), EmailLabels.label)
                            .where { EmailLabels.email inList emailIdsWithThisLabel }
                            .andWhere { EmailLabels.label neq label.labelId }
                            .groupBy(EmailLabels.label)
                            .map { row ->
                                val otherLabelId = row[EmailLabels.label].value
                                val count = row[Count(EmailLabels.email)]

                                LabelMap.Label.Relation(
                                    labelId = otherLabelId,
                                    count = count
                                )
                            }

                        LabelMap.Label(
                            id = label.labelId,
                            name = label.name,
                            color = label.color,
                            emailCount = label.emailCount,
                            relations = otherLabels,
                        )
                    }
                )
            }.let {
                call.respond(it)
            }
        }
    }
}

@Serializable
private data class LabelMap(
    @SerialName("labels") val labels: List<Label>,
) {
    @Serializable
    data class Label(
        @SerialName("id") val id: Uuid,
        @SerialName("name") val name: String,
        @SerialName("color") val color: String,
        @JsonSchema.Description("How many mails carry it")
        @SerialName("email_count") val emailCount: Long,
        @JsonSchema.Description("The other labels on its mails")
        @SerialName("relations") val relations: List<Relation>
    ) {
        @Serializable
        data class Relation(
            @SerialName("label_id") val labelId: Uuid,
            @JsonSchema.Description("How many of its mails carry that label too")
            @SerialName("count") val count: Long,
        )
    }
}