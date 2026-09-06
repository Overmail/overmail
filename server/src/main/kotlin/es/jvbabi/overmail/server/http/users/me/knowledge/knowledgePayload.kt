package es.jvbabi.overmail.server.http.users.me.knowledge

import es.jvbabi.overmail.server.database.models.Knowledge
import es.jvbabi.overmail.server.database.models.Knowledges
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.ResultRow

/**
 * One knowledge entry as every route here hands it out -- the listing, and the answer to a write.
 *
 * Dates leave as strings (`2026-03-14` for the day, ISO-8601 for the timestamps), like the rest of
 * this api: a client renders them in its own locale and never computes with them.
 */
@Serializable
data class KnowledgeEntryPayload(
    @SerialName("id") val id: Uuid,
    /** What the entry is about, in a few words. Also its handle -- unique per user. */
    @SerialName("name") val name: String,
    @SerialName("description") val description: String,
    /** The words this entry is found by, normalized; see [Knowledge.joinKeywords]. */
    @SerialName("keywords") val keywords: List<String>,
    /** The day this is about, where that is the point -- a deadline, a move. Usually null. */
    @SerialName("relevant_on") val relevantOn: String?,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    /** Whether the assistant wrote this while chatting or sorting mail, rather than the user. */
    @SerialName("created_by_agent") val createdByAgent: Boolean,
)

/** A row of [Knowledges] as it goes over the wire. Reads the whole row but the owner. */
internal fun ResultRow.toKnowledgeEntryPayload() = KnowledgeEntryPayload(
    id = this[Knowledges.id].value,
    name = this[Knowledges.name],
    description = this[Knowledges.description],
    keywords = Knowledge.splitKeywords(this[Knowledges.keywords]),
    relevantOn = this[Knowledges.relevantOn]?.toString(),
    createdAt = this[Knowledges.createdAt].toString(),
    updatedAt = this[Knowledges.updatedAt].toString(),
    createdByAgent = this[Knowledges.createdByAgent],
)
