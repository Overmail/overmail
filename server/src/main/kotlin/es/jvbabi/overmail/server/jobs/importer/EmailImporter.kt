package es.jvbabi.overmail.server.jobs.importer

import java.io.File
import kotlin.uuid.Uuid

class EmailImporter : AbstractEmailImporter, KoinComponent {
    override suspend fun importEmailFile(emailFile: File, imapAccountId: Uuid) {

    }
}