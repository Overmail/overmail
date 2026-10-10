package es.jvbabi.overmail.server.jobs.importer

import java.io.File
import kotlin.uuid.Uuid

interface AbstractEmailImporter {
    abstract suspend fun importEmailFile(emailFile: File, imapAccountId: Uuid)
}