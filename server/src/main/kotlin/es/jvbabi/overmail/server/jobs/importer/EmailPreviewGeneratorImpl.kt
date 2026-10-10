package es.jvbabi.overmail.server.jobs.importer

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.EmailPreviews
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.upsert

class EmailPreviewGeneratorImpl(
    private val database: OvermailDatabase
) : EmailPreviewGenerator() {

    override suspend fun generatePreview(email: Email) {
        // Parsing HTML is processor work, so it happens off the dispatcher the queries run on.
        val preview = withContext(Dispatchers.Default) { mailPreview(email.textContent, email.htmlContent) }

        database.query {
            EmailPreviews.upsert {
                it[EmailPreviews.email] = email.id
                it[EmailPreviews.preview] = preview
            }
        }
    }
}
