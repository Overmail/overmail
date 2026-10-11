package es.jvbabi.overmail.server.jobs.importer

import es.jvbabi.overmail.kamel.MailFolder
import es.jvbabi.overmail.server.telemetry.span
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.context.Context
import io.opentelemetry.context.ContextKey
import io.opentelemetry.extension.kotlin.asContextElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.DurationUnit
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private const val SCOPE = "es.jvbabi.overmail.importer"

private val ACCOUNT = AttributeKey.stringKey("overmail.account.id")
private val FOLDER = AttributeKey.stringKey("overmail.folder")
private val TRIGGER = AttributeKey.stringKey("overmail.importer.trigger")
private val OUTCOME = AttributeKey.stringKey("overmail.importer.outcome")
private val STEP = AttributeKey.stringKey("overmail.importer.step")
private val WATCH_END = AttributeKey.stringKey("overmail.importer.idle.end")
private val MAIL_RESULT = AttributeKey.stringKey("overmail.importer.mail.result")
private val MAIL_UID = AttributeKey.longKey("overmail.importer.mail.uid")
private val EMAIL = AttributeKey.stringKey("overmail.email.id")
private val FIRST_UID = AttributeKey.longKey("overmail.importer.pass.first_uid")
private val BACKFILL = AttributeKey.booleanKey("overmail.importer.pass.backfill")
private val MAILS_READ = AttributeKey.longKey("overmail.importer.pass.mails_read")
private val MAILS_IMPORTED = AttributeKey.longKey("overmail.importer.pass.mails_imported")

/** Set on a pass that reads a folder from its start, see [ImporterTelemetry.pass]. */
private val SUMMARY_ONLY: ContextKey<Boolean> = ContextKey.named("overmail-importer-summary-only")

/** The default boundaries are made for milliseconds; everything here is recorded in seconds. */
private val DURATION_BUCKETS = listOf(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0, 60.0, 300.0)

/** From "pushed" to "found by a poll after a day offline". */
private val DELAY_BUCKETS = listOf(1.0, 2.0, 5.0, 10.0, 30.0, 60.0, 120.0, 300.0, 600.0, 1800.0, 3600.0, 21600.0, 86400.0)

/**
 * What the importer records, in one place so its parts say what happened and not how it is kept.
 *
 * Two kinds of thing happen in an importer, and they are recorded differently. What has an end --
 * a pass, a mail, a step -- is a span and a duration. What is held open for as long as it works --
 * a running importer, a connection, an `IDLE` -- is a number that goes up and down, because a
 * span is only exported once it ends.
 *
 * Attributes carry ids and folder names, never what a mail says or who it is from.
 */
class ImporterTelemetry(openTelemetry: OpenTelemetry) {

    private val tracer = openTelemetry.getTracer(SCOPE)
    private val meter = openTelemetry.getMeter(SCOPE)

    private val running = meter.upDownCounterBuilder("overmail.importer.running")
        .setDescription("Importers that are running, one per account").setUnit("{importer}").build()
    private val connections = meter.upDownCounterBuilder("overmail.importer.connections")
        .setDescription("Mailboxes an importer is connected to").setUnit("{connection}").build()
    private val connectionFailures = meter.counterBuilder("overmail.importer.connection.failures")
        .setDescription("Connections to a mailbox that failed or were lost").setUnit("{failure}").build()
    private val watches = meter.upDownCounterBuilder("overmail.importer.idle.watches")
        .setDescription("Folders an IDLE is held on").setUnit("{watch}").build()
    private val watchEvents = meter.counterBuilder("overmail.importer.idle.events")
        .setDescription("Changes a server reported on a held IDLE").setUnit("{event}").build()
    private val watchEnds = meter.counterBuilder("overmail.importer.idle.ends")
        .setDescription("IDLEs that ended, by whether they were renewed, ended by themselves or failed").setUnit("{watch}").build()
    private val passes = meter.counterBuilder("overmail.importer.passes")
        .setDescription("Passes over a folder, by what triggered them and how they ended").setUnit("{pass}").build()
    private val passDuration = meter.histogramBuilder("overmail.importer.pass.duration")
        .setDescription("How long a pass that had something to read took").setUnit("s")
        .setExplicitBucketBoundariesAdvice(DURATION_BUCKETS).build()
    private val mails = meter.counterBuilder("overmail.importer.mails")
        .setDescription("Mails a pass came across, by what became of them").setUnit("{mail}").build()
    private val mailDuration = meter.histogramBuilder("overmail.importer.mail.duration")
        .setDescription("How long one mail took, stored and with every step").setUnit("s")
        .setExplicitBucketBoundariesAdvice(DURATION_BUCKETS).build()
    private val mailDelay = meter.histogramBuilder("overmail.importer.mail.delay")
        .setDescription("How long after it was sent a mail was imported").setUnit("s")
        .setExplicitBucketBoundariesAdvice(DELAY_BUCKETS).build()
    private val stepDuration = meter.histogramBuilder("overmail.importer.step.duration")
        .setDescription("How long one step of the import took for one mail").setUnit("s")
        .setExplicitBucketBoundariesAdvice(DURATION_BUCKETS).build()

    /** How an `IDLE` stopped being held. */
    enum class WatchEnd(internal val key: String) {
        /** Given up on purpose, to issue a fresh one. */
        Renewed("renewed"),
        Ended("ended"),
        Failed("failed"),
    }

    fun importerStarted() = running.add(1)
    fun importerStopped() = running.add(-1)

    /** [block] is what happens on one connection to the mailbox of [account]. */
    suspend fun <T> connected(account: ImapConnection, block: suspend () -> T): T {
        val attributes = Attributes.of(ACCOUNT, account.id.toString())
        connections.add(1, attributes)
        try {
            return block()
        } finally {
            connections.add(-1, attributes)
        }
    }

    fun connectionFailed(account: ImapConnection) = connectionFailures.add(1, Attributes.of(ACCOUNT, account.id.toString()))

    /** [block] holds one `IDLE` on [folder] and returns when it is over. */
    suspend fun <T> watching(account: ImapConnection, folder: MailFolder, block: suspend () -> T): T {
        val attributes = folderAttributes(account, folder.fullName)
        watches.add(1, attributes)
        try {
            return block()
        } finally {
            watches.add(-1, attributes)
        }
    }

    fun watchEvent(account: ImapConnection, folder: MailFolder) = watchEvents.add(1, folderAttributes(account, folder.fullName))

    fun watchEnded(account: ImapConnection, folder: MailFolder, end: WatchEnd) =
        watchEnds.add(1, folderAttributes(account, folder.fullName).toBuilder().put(WATCH_END, end.key).build())

    /** A pass that found nothing new. Counted and nothing else: this is what most passes are. */
    fun passUnchanged(context: ImportContext) = passes.add(1, passAttributes(context, "unchanged"))

    /**
     * A pass that reads [context]'s folder from [firstUid] on, as a trace of its own.
     *
     * A pass that starts at the first mail of a folder is a backfill: it may read tens of
     * thousands of mails, which no trace can hold. It gets its one span and every metric, but no
     * spans below it.
     */
    suspend fun <T> pass(context: ImportContext, firstUid: Long, block: suspend (Pass) -> T): T {
        val backfill = firstUid <= 1
        val pass = Pass(context)
        val started = TimeSource.Monotonic.markNow()
        var outcome = "completed"
        try {
            return tracer.span("importer.pass", {
                setNoParent()
                setAllAttributes(folderAttributes(context.account, context.folder.folder))
                setAttribute(TRIGGER, context.trigger.key)
                setAttribute(FIRST_UID, firstUid)
                setAttribute(BACKFILL, backfill)
            }) { span ->
                try {
                    withContext(Context.current().with(SUMMARY_ONLY, backfill).asContextElement()) { block(pass) }
                } finally {
                    span.setAttribute(MAILS_READ, pass.read)
                    span.setAttribute(MAILS_IMPORTED, pass.imported)
                }
            }
        } catch (e: CancellationException) {
            outcome = "cancelled"
            throw e
        } catch (e: Throwable) {
            outcome = "failed"
            throw e
        } finally {
            passes.add(1, passAttributes(context, outcome))
            passDuration.record(
                started.seconds(),
                folderAttributes(context.account, context.folder.folder).toBuilder().put(OUTCOME, outcome).build(),
            )
        }
    }

    /** One pass over a folder, and what it came across. */
    inner class Pass internal constructor(private val context: ImportContext) {
        internal var read = 0L
        internal var imported = 0L

        /** [block] takes the mail [uid] all the way in. A failure is counted and goes on up. */
        suspend fun mail(uid: Long, block: suspend () -> EmailInserter.Result): EmailInserter.Result {
            val started = TimeSource.Monotonic.markNow()
            read++
            val result = try {
                detail("importer.mail", { setAttribute(MAIL_UID, uid) }) { span ->
                    block().also { result ->
                        span.setAttribute(MAIL_RESULT, result.key)
                        if (result is EmailInserter.Result.Imported) span.setAttribute(EMAIL, result.email.id.value.toString())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                count("failed")
                throw e
            }

            count(result.key)
            mailDuration.record(started.seconds(), Attributes.of(MAIL_RESULT, result.key))
            if (result is EmailInserter.Result.Imported) {
                imported++
                // Only for a mail that is news, the same line the push draws: one that was sent
                // before its folder was added arrives as late as the folder was added.
                val sent = result.email.sent
                if (sent >= context.folder.createdAt) {
                    mailDelay.record(
                        (Clock.System.now() - sent).toDouble(DurationUnit.SECONDS).coerceAtLeast(0.0),
                        folderAttributes(context.account, context.folder.folder),
                    )
                }
            }
            return result
        }

        /** A mail the server could not hand out in a readable form. */
        fun unreadable() {
            read++
            count("unreadable")
        }

        /** A mail that failed in so many passes that it is left behind. */
        fun abandoned() = count("abandoned")

        private fun count(result: String) = mails.add(
            1,
            folderAttributes(context.account, context.folder.folder).toBuilder().put(MAIL_RESULT, result).build(),
        )
    }

    /** [block] stores one mail, or finds that it is stored already. */
    suspend fun insert(block: suspend () -> EmailInserter.Result): EmailInserter.Result =
        detail("importer.insert") { block() }

    /** [block] runs [step] for one mail. A failure is recorded and goes on up. */
    suspend fun step(step: ImportStep, block: suspend () -> Unit) {
        val name = step::class.simpleName ?: "step"
        val started = TimeSource.Monotonic.markNow()
        var outcome = "ok"
        try {
            detail("importer.$name") { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            outcome = "failed"
            throw e
        } finally {
            stepDuration.record(started.seconds(), Attributes.of(STEP, name, OUTCOME, outcome))
        }
    }

    /** A span below the current one, unless the pass it belongs to is recorded in summary only. */
    private suspend fun <T> detail(name: String, configure: SpanBuilder.() -> Unit = {}, block: suspend (Span) -> T): T =
        if (Context.current().get(SUMMARY_ONLY) == true) block(Span.getInvalid()) else tracer.span(name, configure, block)

    private fun folderAttributes(account: ImapConnection, folder: String) =
        Attributes.of(ACCOUNT, account.id.toString(), FOLDER, folder)

    private fun passAttributes(context: ImportContext, outcome: String) =
        folderAttributes(context.account, context.folder.folder).toBuilder()
            .put(TRIGGER, context.trigger.key)
            .put(OUTCOME, outcome)
            .build()

    companion object {
        /** Records nothing, for whatever is built without telemetry: a test, mostly. */
        val Disabled = ImporterTelemetry(OpenTelemetry.noop())
    }
}

private val EmailInserter.Result.key
    get() = when (this) {
        is EmailInserter.Result.Imported -> "imported"
        is EmailInserter.Result.AlreadyExists -> "already_exists"
        is EmailInserter.Result.Rejected -> "rejected"
    }

private fun TimeMark.seconds() = elapsedNow().toDouble(DurationUnit.SECONDS)
