package es.jvbabi.overmail.server.database.mappers

import es.jvbabi.overmail.server.ai.AgentLine
import es.jvbabi.overmail.server.database.models.EmailAiClassifications
import es.jvbabi.overmail.server.domain.models.EmailAiClassification
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow

/**
 * The stored run as the lines it was made of.
 *
 * A history that cannot be read comes back empty rather than throwing: the row is a record of
 * something that already happened, and a reader looking at a mail is better served by its counts and
 * its model than by an exception. That case means the shape of [AgentLine] moved under rows written
 * by an older version -- which is why the names in it are spelled out rather than derived.
 */
fun ResultRow.toEmailAiClassification(): EmailAiClassification = EmailAiClassification(
    id = this[EmailAiClassifications.id].value,
    emailId = this[EmailAiClassifications.email].value,
    reason = this[EmailAiClassifications.reason],
    history = storedHistory(this[EmailAiClassifications.history]),
    tokensIn = this[EmailAiClassifications.tokensIn],
    tokensOut = this[EmailAiClassifications.tokensOut],
    provider = this[EmailAiClassifications.provider],
    model = this[EmailAiClassifications.model],
    fastModel = this[EmailAiClassifications.fastModel],
    startedAt = this[EmailAiClassifications.startedAt],
    finishedAt = this[EmailAiClassifications.finishedAt],
)

/** The lines as a column. */
fun List<AgentLine>.asStoredHistory(): String = HISTORY_JSON.encodeToString(this)

/** And back. Empty for a column that cannot be read as lines, see above. */
fun storedHistory(stored: String): List<AgentLine> = runCatching {
    HISTORY_JSON.decodeFromString<List<AgentLine>>(stored)
}.getOrDefault(emptyList())

/**
 * Lenient in one direction only: a field added to a line later must not make the runs already stored
 * unreadable, while a field missing from one of them is a row that was written wrong.
 */
private val HISTORY_JSON = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}
