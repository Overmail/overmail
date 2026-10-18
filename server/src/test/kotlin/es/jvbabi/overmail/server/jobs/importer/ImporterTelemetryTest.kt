package es.jvbabi.overmail.server.jobs.importer

import es.jvbabi.overmail.kamel.IdleEvent
import es.jvbabi.overmail.server.database.models.Email
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import es.jvbabi.overmail.kamel.Email as KamelEmail

private val TRIGGER = AttributeKey.stringKey("overmail.importer.trigger")
private val OUTCOME = AttributeKey.stringKey("overmail.importer.outcome")
private val MAIL_RESULT = AttributeKey.stringKey("overmail.importer.mail.result")
private val MAILS_IMPORTED = AttributeKey.longKey("overmail.importer.pass.mails_imported")

private class PreviewStep : ImportStep {
    override suspend fun run(email: Email, context: ImportContext) = Unit
}

private class BrokenStep : ImportStep {
    override suspend fun run(email: Email, context: ImportContext) = error("no preview today")
}

/** What an import leaves behind for whoever watches the server, the real parts on a fake mailbox. */
@OptIn(ExperimentalCoroutinesApi::class)
class ImporterTelemetryTest {

    private val spans = InMemorySpanExporter.create()
    private val metrics = InMemoryMetricReader.create()
    private val telemetry = ImporterTelemetry(
        OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metrics).build())
            .build()
    )

    private val database = testDatabase("importer-telemetry")

    private fun synchronizer(vararg steps: ImportStep, inserter: EmailInserter = EmailInserterImpl(database)) =
        FolderSynchronizerImpl(database, telemetry, EmailImportPipelineImpl(inserter, steps.toList(), telemetry))

    /** The sum of the counter [name] over the points [where] holds for. */
    private fun counted(name: String, where: (io.opentelemetry.api.common.Attributes) -> Boolean = { true }): Long =
        metrics.collectAllMetrics().filter { it.name == name }.flatMap { it.longSumData.points }
            .filter { where(it.attributes) }.sumOf { it.value }

    private fun SpanData.isBelow(parent: SpanData) = traceId == parent.traceId && parentSpanId == parent.spanId

    @Test
    fun `a pass is a trace with the mail, its insert and every step below it`() = runBlocking {
        val account = database.addAccount()
        val folder = FakeFolder(mails = listOf(mail(1)))
        val synchronizer = synchronizer(PreviewStep())
        synchronizer.synchronize(folder, ImportContext(account, folderSync()))
        spans.reset()

        folder.mails += mail(2)
        synchronizer.synchronize(folder, ImportContext(account, folderSync(), ImportTrigger.IdleChange))

        val byName = spans.finishedSpanItems.associateBy { it.name }
        assertEquals(setOf("importer.pass", "importer.mail", "importer.insert", "importer.PreviewStep"), byName.keys)
        val pass = byName.getValue("importer.pass")
        val mail = byName.getValue("importer.mail")
        assertTrue(mail.isBelow(pass))
        assertTrue(byName.getValue("importer.insert").isBelow(mail))
        assertTrue(byName.getValue("importer.PreviewStep").isBelow(mail))

        assertEquals("idle_change", pass.attributes.get(TRIGGER))
        assertEquals(1, pass.attributes.get(MAILS_IMPORTED))
        assertEquals("imported", mail.attributes.get(MAIL_RESULT))
    }

    @Test
    fun `a folder read from its start is one span, with every mail still counted`() = runBlocking {
        val folder = FakeFolder(mails = listOf(mail(1), mail(2), mail(3)))

        synchronizer(PreviewStep()).synchronize(folder, ImportContext(database.addAccount(), folderSync()))

        assertEquals(listOf("importer.pass"), spans.finishedSpanItems.map { it.name })
        assertEquals(3, spans.finishedSpanItems.single().attributes.get(MAILS_IMPORTED))
        assertEquals(3, counted("overmail.importer.mails") { it.get(MAIL_RESULT) == "imported" })
    }

    @Test
    fun `a pass with nothing new is counted and leaves no trace`() = runBlocking {
        val context = ImportContext(database.addAccount(), folderSync())
        val folder = FakeFolder(mails = listOf(mail(1)))
        val synchronizer = synchronizer()
        synchronizer.synchronize(folder, context)
        spans.reset()

        synchronizer.synchronize(folder, context)

        assertEquals(emptyList(), spans.finishedSpanItems)
        assertEquals(1, counted("overmail.importer.passes") { it.get(OUTCOME) == "unchanged" })
        assertEquals(1, counted("overmail.importer.passes") { it.get(OUTCOME) == "completed" })
    }

    @Test
    fun `an idle event with nothing new is counted as unchanged`() = runBlocking {
        val account = database.addAccount()
        val folder = FakeFolder(mails = listOf(mail(1)))
        val synchronizer = synchronizer()
        synchronizer.synchronize(folder, ImportContext(account, folderSync()))

        synchronizer.synchronize(folder, ImportContext(account, folderSync(), ImportTrigger.IdleChange))

        assertEquals(1, counted("overmail.importer.passes") { it.get(TRIGGER) == "idle_change" && it.get(OUTCOME) == "unchanged" })
        assertEquals(0, counted("overmail.importer.passes") { it.get(TRIGGER) == "idle_change" && it.get(OUTCOME) == "completed" })
    }

    @Test
    fun `a mail that cannot be stored fails its span and the pass`() = runBlocking {
        val context = ImportContext(database.addAccount(), folderSync())
        val folder = FakeFolder(mails = listOf(mail(1)))
        val working = synchronizer()
        working.synchronize(folder, context)
        spans.reset()

        folder.mails += mail(2)
        val failing = synchronizer(inserter = object : EmailInserter {
            override suspend fun importEmailIntoDatabase(mail: KamelEmail, account: ImapConnection) =
                throw IOException("the database is gone")
        })
        assertFailsWith<IOException> { failing.synchronize(folder, context) }

        val byName = spans.finishedSpanItems.associateBy { it.name }
        assertEquals(StatusCode.ERROR, byName.getValue("importer.mail").status.statusCode)
        assertEquals(StatusCode.ERROR, byName.getValue("importer.pass").status.statusCode)
        assertEquals(1, counted("overmail.importer.mails") { it.get(MAIL_RESULT) == "failed" })
        assertEquals(1, counted("overmail.importer.passes") { it.get(OUTCOME) == "failed" })
    }

    @Test
    fun `a step that fails is the only span that does`() = runBlocking {
        val context = ImportContext(database.addAccount(), folderSync())
        val folder = FakeFolder(mails = listOf(mail(1)))
        val synchronizer = synchronizer(BrokenStep(), PreviewStep())
        synchronizer.synchronize(folder, context)
        spans.reset()

        folder.mails += mail(2)
        synchronizer.synchronize(folder, context)

        val failed = spans.finishedSpanItems.filter { it.status.statusCode == StatusCode.ERROR }.map { it.name }
        assertEquals(listOf("importer.BrokenStep"), failed)
    }

    @Test
    fun `a watched folder counts its idle and names what made it due`() = runTest {
        val triggers = mutableListOf<ImportTrigger>()
        val inbox = FakeFolder("INBOX")
        val connection = database.addAccount(listOf(folderSync(imapPush = true)))
        val importer = MailboxImporter(
            connection,
            synchronizer = { _, context -> triggers += context.trigger },
            connect = { FakeClient(inbox) },
            telemetry = telemetry,
        )

        val running = backgroundScope.launch { importer.run() }
        runCurrent()
        inbox.idleEvents.emit(IdleEvent.NewMessage(1))
        runCurrent()

        assertEquals(ImportTrigger.IdleChange, triggers.last())
        assertEquals(1, counted("overmail.importer.connections"))
        assertEquals(1, counted("overmail.importer.idle.watches"))
        assertEquals(1, counted("overmail.importer.idle.events"))

        running.cancel()
        runCurrent()
        assertEquals(0, counted("overmail.importer.connections"))
        assertEquals(0, counted("overmail.importer.idle.watches"))
    }
}
