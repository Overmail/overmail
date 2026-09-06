package es.jvbabi.overmail.server.http.users.me.knowledge

import es.jvbabi.overmail.server.database.models.Knowledge
import es.jvbabi.overmail.server.http.api.invalidRequest
import kotlinx.datetime.LocalDate

/** The longest name the column takes; see `Knowledges.name`. */
private const val MAX_NAME_LENGTH = 255

/** A write request as it goes into the row: normalized the way the agent's writes are. */
internal data class KnowledgeInput(
    val name: String,
    val description: String,
    /** Already comma-joined, deduplicated and capped -- see [Knowledge.joinKeywords]. */
    val keywords: String,
    val relevantOn: LocalDate?,
)

/**
 * Checks and normalizes what a screen sent, or ends the request with 400.
 *
 * The same normalization the assistant's own writes go through, so an entry a user typed and one
 * the agent learned are looked up by the same rules -- it is the [Knowledge] companion that owns
 * them, this only decides what is not worth storing at all.
 */
internal fun readKnowledgeInput(
    name: String,
    description: String,
    keywords: List<String>,
    relevantOn: String?,
): KnowledgeInput {
    val cleanName = Knowledge.normalizeName(name)
    if (cleanName.isEmpty()) invalidRequest("name", "an entry needs a name")
    if (cleanName.length > MAX_NAME_LENGTH) {
        invalidRequest("name", "is longer than $MAX_NAME_LENGTH characters", cleanName.length.toString())
    }

    val cleanDescription = description.trim()
    if (cleanDescription.isEmpty()) invalidRequest("description", "an entry needs a description")

    // Null and "" are the same answer -- most entries are not about a day, and a cleared date
    // field arrives as either one depending on the form.
    val day = relevantOn?.takeIf { it.isNotBlank() }?.let { raw ->
        runCatching { LocalDate.parse(raw.trim()) }.getOrNull()
            ?: invalidRequest("relevant_on", "is not a date of the form YYYY-MM-DD", raw)
    }

    return KnowledgeInput(
        name = cleanName,
        description = cleanDescription,
        keywords = Knowledge.joinKeywords(keywords),
        relevantOn = day,
    )
}
