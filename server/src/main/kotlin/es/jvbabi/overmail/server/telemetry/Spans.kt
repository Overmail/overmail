package es.jvbabi.overmail.server.telemetry

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.extension.kotlin.asContextElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * Runs [block] in a span of its own, below the span the caller is in unless [configure] says
 * otherwise.
 *
 * The span is the current one for all of [block], across suspension points and dispatchers --
 * OpenTelemetry keeps it per thread, which a coroutine does not stay on. So the spans [block]
 * opens end up below this one, and what it logs carries this trace.
 *
 * A failure marks the span and goes on up. Being cancelled is not one.
 */
suspend fun <T> Tracer.span(
    name: String,
    configure: SpanBuilder.() -> Unit = {},
    block: suspend (Span) -> T,
): T {
    val span = spanBuilder(name).apply(configure).startSpan()
    return try {
        withContext(span.asContextElement()) { block(span) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        span.recordException(e)
        span.setStatus(StatusCode.ERROR)
        throw e
    } finally {
        span.end()
    }
}
