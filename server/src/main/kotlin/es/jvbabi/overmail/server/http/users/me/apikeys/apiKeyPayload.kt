package es.jvbabi.overmail.server.http.users.me.apikeys

import es.jvbabi.overmail.server.database.models.ApiKeys
import io.ktor.openapi.JsonSchema
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.ResultRow
import kotlin.uuid.Uuid

/** One api key as a list shows it. The key itself is not in here: it is shown once, on creation. */
@Serializable
data class ApiKeyPayload(
    @SerialName("id") val id: Uuid,
    @SerialName("name") val name: String,
    @JsonSchema.Description("The start of the key, enough to recognise it and not enough to use it")
    @SerialName("prefix") val prefix: String,
    @JsonSchema.Description("When the key was made, in whole seconds since the epoch")
    @SerialName("created_at") val createdAt: Long,
    @JsonSchema.Description("When a request last signed in with the key, to the minute, in whole seconds since the epoch; null when it was never used")
    @SerialName("last_used_at") val lastUsedAt: Long?,
)

/** Reads a row selected with every column of [ApiKeys] but the hash. */
internal fun ResultRow.toApiKeyPayload() = ApiKeyPayload(
    id = this[ApiKeys.id].value,
    name = this[ApiKeys.name],
    prefix = this[ApiKeys.keyPrefix],
    createdAt = this[ApiKeys.createdAt].epochSeconds,
    lastUsedAt = this[ApiKeys.lastUsedAt]?.epochSeconds,
)
