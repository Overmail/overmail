package es.jvbabi.overmail.server.jobs.importer

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Attachments
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.util.embeddedContentId
import es.jvbabi.overmail.server.util.referencedContentIds
import kotlinx.coroutines.CancellationException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import kotlin.uuid.Uuid
import es.jvbabi.overmail.kamel.Email as KamelEmail

/** How many mails are looked up at a time. Each one is parsed from its source. */
private const val BATCH_SIZE = 50

/**
 * Fills [Attachments.contentId] in for attachments stored before anything kept it, so the images
 * of older mails show in their body too. The content id was not stored, so it comes out of the
 * source of the mail, parsed again. Runs once at startup and then has nothing to do: every
 * attachment imported since is written with it, see [EmailInserterImpl].
 *
 * What bounds it: every attachment of a mail it looks at comes back with [Attachments.isEmbedded]
 * set, found again in the source or not, so the set of attachments without one only shrinks.
 */
class EmbeddedImageBackfill(private val database: OvermailDatabase) {

    private val logger = LoggerFactory.getLogger(EmbeddedImageBackfill::class.java)

    suspend fun run() {
        var embedded = 0

        try {
            while (true) {
                val emailIds = database.query {
                    Attachments
                        .select(Attachments.email)
                        .where { Attachments.isEmbedded.isNull() }
                        .withDistinct()
                        .limit(BATCH_SIZE)
                        .map { row -> row[Attachments.email].value }
                }

                if (emailIds.isEmpty()) break

                for (emailId in emailIds) embedded += backfill(emailId)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (cause: Exception) {
            // Not rethrown: those mails show their images as attachments, as before. The next
            // start tries again.
            logger.warn("Could not backfill embedded images: ${cause.message}")
        }

        if (embedded > 0) logger.info("Found $embedded image(s) embedded in the body of older mails")
    }

    /** @return how many attachments of [emailId] turned out to be images its body shows. */
    private suspend fun backfill(emailId: Uuid): Int = database.query {
        val email = Emails
            .select(Emails.htmlContent, Emails.rawContent)
            .where { Emails.id eq emailId }
            .single()
        val referenced = referencedContentIds(email[Emails.htmlContent])

        // Only a body that references something needs its source parsed.
        val parsed = if (referenced.isEmpty()) emptyList() else try {
            KamelEmail.parse(email[Emails.rawContent]).getContent(includeAttachments = true).attachments
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (cause: Exception) {
            logger.debug("Could not parse the source of $emailId again: ${cause.message}")
            emptyList()
        }

        // Told apart the way they were stored, see EmailInserterImpl: a part found once is used up,
        // so two identical images both find theirs.
        val unmatched = parsed.withIndex().toMutableList()
        val stored = Attachments
            .select(Attachments.id, Attachments.filename, Attachments.contentType, Attachments.size)
            .where { (Attachments.email eq emailId) and Attachments.isEmbedded.isNull() }
            .toList()

        var embedded = 0
        for (row in stored) {
            val match = unmatched.firstOrNull { (index, attachment) ->
                (attachment.fileName ?: "attachment-$index").take(255) == row[Attachments.filename] &&
                    attachment.contentType.take(255) == row[Attachments.contentType] &&
                    attachment.data.size.toLong() == row[Attachments.size]
            }
            if (match != null) unmatched.remove(match)
            val contentId = match?.value?.let { embeddedContentId(it.contentId, it.contentType, referenced) }
            if (contentId != null) embedded++

            Attachments.update({ Attachments.id eq row[Attachments.id] }) {
                it[Attachments.contentId] = contentId
                it[isEmbedded] = contentId != null
            }
        }
        embedded
    }
}
