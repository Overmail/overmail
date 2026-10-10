package es.jvbabi.overmail.server.jobs.importer

import es.jvbabi.overmail.kamel.Email as KamelEmail

fun interface EmailImportPipeline {
    /**
     * Takes [mail] all the way in: stored, and everything that follows from that done.
     *
     * Finishes what it started even if the caller is cancelled meanwhile, so a mail is never left
     * stored without its follow-up. Cancellation takes hold between two mails.
     *
     * @throws Exception if the mail could not be stored for a reason that may pass -- the
     * connection, the database. Nothing of it is stored then.
     */
    suspend fun import(mail: KamelEmail, context: ImportContext): EmailInserter.Result
}
