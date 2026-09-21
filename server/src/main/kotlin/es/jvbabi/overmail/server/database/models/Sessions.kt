package es.jvbabi.overmail.server.database.models

import es.jvbabi.overmail.server.database.OvermailDatabase
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.json.jsonb
import kotlin.uuid.Uuid

class Session(id: EntityID<Uuid>): UuidEntity(id) {
    companion object : UuidEntityClass<Session>(Sessions)

    var user by User referencedOn Sessions.user
    var client by Sessions.client
    var issuedAt by Sessions.issuedAt
    var revokedAt by Sessions.revokedAt
    var token by Sessions.token

    @Serializable
    sealed class Client {
        @Serializable
        @SerialName("web")
        data class Web(
            @SerialName("browser") val browser: String,
            @SerialName("device") val device: String,
            @SerialName("os") val os: String,
        ) : Client()

        @Serializable
        @SerialName("android")
        data class Android(
            @SerialName("device") val device: String,
            @SerialName("manufacturer") val manufacturer: String,
            @SerialName("os") val os: String,
        ) : Client()

        @Serializable
        @SerialName("ios")
        data class Ios(
            @SerialName("device") val device: String,
            @SerialName("os") val os: String,
        ) : Client()

        companion object {
            const val UNKNOWN = "unknown"
        }
    }
}

object Sessions : UuidTable("sessions") {
    val user = reference("user", Users, onDelete = ReferenceOption.CASCADE)
    val client = jsonb<Session.Client>("client", OvermailDatabase.json)
    val issuedAt = timestamp("issued_at").defaultExpression(CurrentTimestamp)
    val revokedAt = timestamp("revoked_at").nullable().default(null)

    /** Looked up on every authenticated request, and unique so a token can be adopted only once. */
    val token = text("token").uniqueIndex()
}
