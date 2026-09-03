package es.jvbabi.overmail.server.jobs.avatar

import es.jvbabi.overmail.server.data.avatar.circlePadding
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.EmailAvatar
import es.jvbabi.overmail.server.database.models.EmailAvatars
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory

/** How many pictures are read out of the database at a time. Each one is a blob. */
private const val BATCH_SIZE = 50

/**
 * Fills [EmailAvatars.circlePadding] in for pictures that were stored before anything looked at
 * their shape. Runs once at startup and then has nothing to do: every picture written since is
 * analysed where it is written, see [AvatarQueue].
 *
 * A picture nothing can decode is stored as `0.0` rather than left null, which is also what bounds
 * this: every row it reads comes back with a value, so the set of null rows only shrinks.
 */
class AvatarShapeBackfill(private val database: OvermailDatabase) {

    private val logger = LoggerFactory.getLogger(AvatarShapeBackfill::class.java)

    suspend fun run() {
        var analysed = 0

        try {
            while (true) {
                // Ids first, bytes per row: a batch of blobs held in one list is a batch of whole
                // pictures in memory, and there is no reason for more than one at a time.
                val ids = database.query {
                    EmailAvatars
                        .select(EmailAvatars.id)
                        .where { EmailAvatars.circlePadding.isNull() }
                        .limit(BATCH_SIZE)
                        .map { row -> row[EmailAvatars.id].value }
                }

                if (ids.isEmpty()) break

                for (id in ids) {
                    if (analyse(id)) analysed++
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (cause: Exception) {
            // Not rethrown: the app serves mail perfectly well with the column unfilled, the ui
            // just pads those pictures. The next start tries again.
            logger.warn("Could not backfill avatar shapes: ${cause.message}")
        }

        if (analysed > 0) logger.info("Analysed the shape of $analysed avatar(s)")
    }

    /** @return whether the row was still there to be analysed. */
    private suspend fun analyse(id: EmailAvatar.Id): Boolean {
        val data = database.query {
            EmailAvatars
                .selectAll()
                .where { EmailAvatars.id eq id }
                .firstOrNull()
                ?.get(EmailAvatars.data)
        } ?: return false

        val padding = withContext(Dispatchers.Default) { data.circlePadding() }
        if (padding == null) logger.debug("Could not decode avatar $id, so it gets no padding")

        database.query {
            EmailAvatars.update({ EmailAvatars.id eq id }) { it[circlePadding] = padding ?: 0.0 }
        }

        return true
    }
}
