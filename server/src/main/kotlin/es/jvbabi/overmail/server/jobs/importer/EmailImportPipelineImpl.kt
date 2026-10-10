package es.jvbabi.overmail.server.jobs.importer

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import es.jvbabi.overmail.kamel.Email as KamelEmail

/**
 * [inserter] first, then every one of [steps] for a mail that was new. A step that fails is
 * logged and costs the steps after it nothing: the mail is stored by then and would never come
 * through here again.
 */
class EmailImportPipelineImpl(
    private val inserter: EmailInserter,
    private val steps: List<ImportStep>,
) : EmailImportPipeline {

    private val logger = LoggerFactory.getLogger(EmailImportPipelineImpl::class.java)

    override suspend fun import(mail: KamelEmail, context: ImportContext): EmailInserter.Result =
        withContext(NonCancellable) {
            val result = inserter.importEmailIntoDatabase(mail, context.account)

            if (result is EmailInserter.Result.Imported) steps.forEach { step ->
                try {
                    step.run(result.email, context)
                } catch (e: Exception) {
                    logger.error("${step::class.simpleName} failed for mail ${result.email.id.value}", e)
                }
            }

            result
        }
}
