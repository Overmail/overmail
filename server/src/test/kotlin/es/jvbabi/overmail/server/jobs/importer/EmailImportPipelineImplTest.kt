package es.jvbabi.overmail.server.jobs.importer

import es.jvbabi.overmail.server.jobs.importer.EmailInserter.Result
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.uuid.Uuid

/** The order of what happens to one mail, and what a failure in it costs. */
class EmailImportPipelineImplTest {

    private val database = testDatabase("pipeline")
    private val ran = mutableListOf<String>()

    private fun step(name: String, fails: Boolean = false) = ImportStep { _, _ ->
        ran += name
        if (fails) error("$name failed")
    }

    private fun pipeline(vararg steps: ImportStep) = EmailImportPipelineImpl(EmailInserterImpl(database), steps.toList())

    @Test
    fun `a new mail goes through every step in order`() = runBlocking {
        val context = ImportContext(database.addAccount(), folderSync())

        val result = pipeline(step("preview"), step("classify"), step("notify")).import(mail(1), context)

        assertIs<Result.Imported>(result)
        assertEquals(listOf("preview", "classify", "notify"), ran)
    }

    @Test
    fun `a step that fails costs the steps after it nothing`() = runBlocking {
        val context = ImportContext(database.addAccount(), folderSync())

        val result = pipeline(step("preview", fails = true), step("notify")).import(mail(1), context)

        assertIs<Result.Imported>(result)
        assertEquals(listOf("preview", "notify"), ran)
    }

    @Test
    fun `a mail that is already there runs no step`() = runBlocking {
        val context = ImportContext(database.addAccount(), folderSync())
        val pipeline = pipeline(step("notify"))

        pipeline.import(mail(1), context)
        val again = pipeline.import(mail(1), context)

        assertEquals(Result.AlreadyExists, again)
        assertEquals(listOf("notify"), ran)
    }

    @Test
    fun `a mail that was started is finished when the caller is cancelled`() = runBlocking {
        val context = ImportContext(database.addAccount(), folderSync())
        val inFirstStep = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pipeline = pipeline(
            ImportStep { _, _ -> inFirstStep.complete(Unit); release.await() },
            step("notify"),
        )

        val job = launch { pipeline.import(mail(1), context) }
        inFirstStep.await()
        val cancelling = launch { job.cancelAndJoin() }
        release.complete(Unit)
        cancelling.join()

        assertEquals(listOf("notify"), ran)
    }

    @Test
    fun `the classify step only takes what the folder's setting covers`() = runBlocking {
        val account = database.addAccount()
        val queued = mutableListOf<Uuid>()
        val pipeline = EmailImportPipelineImpl(EmailInserterImpl(database), listOf(ClassifyStep(queued::add)))
        // Folders are added on 2026-06-01T12:00:00Z; both mails are sent within that minute.
        val onlyNew = folderSync(aiImportSettings = es.jvbabi.overmail.server.database.models.ImapAccountFolderSync.AiImportSettings.AfterDate(kotlin.time.Instant.parse("2026-06-01T12:00:30Z")))

        val before = assertIs<Result.Imported>(pipeline.import(mail(10), ImportContext(account, onlyNew))).email
        val after = assertIs<Result.Imported>(pipeline.import(mail(40), ImportContext(account, onlyNew))).email

        assertEquals(listOf(after.id.value), queued)
        assertEquals(false, before.id.value in queued)
    }
}
