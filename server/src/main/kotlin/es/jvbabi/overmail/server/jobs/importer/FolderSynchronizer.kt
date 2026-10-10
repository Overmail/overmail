package es.jvbabi.overmail.server.jobs.importer

import es.jvbabi.overmail.kamel.MailFolder

fun interface FolderSynchronizer {
    /**
     * Imports what [folder] received since the last call and remembers how far it got.
     *
     * @throws Exception if the pass had to stop early. What it imported until then is kept, and
     * the next call goes on from there.
     */
    suspend fun synchronize(folder: MailFolder, context: ImportContext)
}
