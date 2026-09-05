package es.jvbabi.overmail.server.http.labels

import es.jvbabi.overmail.server.database.models.Labels
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.requestedIds
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlin.uuid.Uuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select

/**
 * What the labels behind a list of ids are called: `GET /api/labels?ids=a,b,c`.
 *
 * Only the user's own labels; an id that is not theirs is missing from the answer like an
 * unknown one.
 */
fun Route.labelsByIds() {
    authenticate {
        get {
            val ids = call.requestedIds()
            if (ids.isEmpty()) return@get call.respond(LabelsResponse(emptyList()))

            val userId = call.requireAuthenticatedUserId()

            val labels = call.database().query {
                Labels
                    .select(Labels.id, Labels.name, Labels.color)
                    .where { (Labels.id inList ids) and (Labels.owner eq userId) }
                    .map { row ->
                        LabelsResponse.Label(
                            id = row[Labels.id].value,
                            name = row[Labels.name],
                            color = row[Labels.color],
                        )
                    }
            }

            call.respond(LabelsResponse(labels))
        }
    }
}

@Serializable
private data class LabelsResponse(
    @SerialName("labels") val labels: List<Label>,
) {
    @Serializable
    data class Label(
        @SerialName("id") val id: Uuid,
        @SerialName("name") val name: String,
        @SerialName("color") val color: String,
    )
}
