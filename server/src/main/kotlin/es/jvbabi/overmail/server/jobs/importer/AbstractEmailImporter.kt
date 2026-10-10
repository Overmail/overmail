package es.jvbabi.overmail.server.jobs.importer

import es.jvbabi.overmail.server.database.models.ImapAccount
import java.io.File

interface AbstractEmailImporter {
    suspend fun importEmailFile(emailFile: File, imapAccount: ImapAccount): EmailImporter.Result
}
