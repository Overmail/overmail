package es.jvbabi.overmail.server.telemetry

import ch.qos.logback.classic.LoggerContext
import es.jvbabi.overmail.server.config.TelemetryConfig
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import io.opentelemetry.instrumentation.runtimetelemetry.RuntimeTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.UUID

private const val LOG_APPENDER_NAME = "OTLP"

/**
 * Traces, metrics and logs, exported over OTLP (http/protobuf) to the collector the config names.
 *
 * [openTelemetry] is what everything records on. Without a config it is the no-op instance, so
 * nothing that records carries a case for telemetry being switched off.
 */
class Telemetry private constructor(
    val openTelemetry: OpenTelemetry,
    private val resources: List<AutoCloseable>,
) : AutoCloseable {

    /** Sends what is still buffered and lets go of everything [start] set up. */
    override fun close() = resources.forEach { resource ->
        try {
            resource.close()
        } catch (e: Exception) {
            logger.warn("Could not shut telemetry down cleanly", e)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(Telemetry::class.java)

        fun start(config: TelemetryConfig?): Telemetry {
            if (config == null) {
                logger.info("No telemetry section in the config, nothing is exported")
                return Telemetry(OpenTelemetry.noop(), emptyList())
            }

            val sdk = sdk(config)
            // The JVM's own numbers: memory, garbage collection, threads, cpu.
            val runtime = RuntimeTelemetry.create(sdk)
            val logs = exportLogs(sdk)
            logger.info("Exporting telemetry to ${config.endpoint}")

            // The sdk last: closing it sends what the two before it still recorded.
            return Telemetry(sdk, listOf(runtime, logs, sdk))
        }

        private fun sdk(config: TelemetryConfig): OpenTelemetrySdk {
            val endpoint = config.endpoint.trimEnd('/')
            val resource = Resource.getDefault().merge(
                Resource.create(
                    Attributes.builder()
                        .put("service.name", config.serviceName)
                        // Tells two servers behind one service name apart, and a restart from what ran before.
                        .put("service.instance.id", UUID.randomUUID().toString())
                        .apply { config.resource.forEach { (key, value) -> put(key, value) } }
                        .build()
                )
            )

            val spans = OtlpHttpSpanExporter.builder().setEndpoint("$endpoint/v1/traces")
                .apply { config.headers.forEach { (name, value) -> addHeader(name, value) } }.build()
            val metrics = OtlpHttpMetricExporter.builder().setEndpoint("$endpoint/v1/metrics")
                .apply { config.headers.forEach { (name, value) -> addHeader(name, value) } }.build()
            val logs = OtlpHttpLogRecordExporter.builder().setEndpoint("$endpoint/v1/logs")
                .apply { config.headers.forEach { (name, value) -> addHeader(name, value) } }.build()

            return OpenTelemetrySdk.builder()
                .setTracerProvider(
                    SdkTracerProvider.builder().setResource(resource)
                        .addSpanProcessor(BatchSpanProcessor.builder(spans).build()).build()
                )
                .setMeterProvider(
                    SdkMeterProvider.builder().setResource(resource)
                        .registerMetricReader(PeriodicMetricReader.builder(metrics).build()).build()
                )
                .setLoggerProvider(
                    SdkLoggerProvider.builder().setResource(resource)
                        .addLogRecordProcessor(BatchLogRecordProcessor.builder(logs).build()).build()
                )
                .build()
        }

        /**
         * Hands everything that is logged to [sdk] as well, next to the console. A line written
         * inside a span carries its trace, which is what links the two in the backend.
         *
         * Attached in code rather than in `logback.xml`: an appender declared there buffers what
         * it is given until it is handed an instance, and without a config it never is.
         */
        private fun exportLogs(sdk: OpenTelemetrySdk): AutoCloseable {
            val context = LoggerFactory.getILoggerFactory() as LoggerContext
            val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
            val appender = OpenTelemetryAppender().apply {
                name = LOG_APPENDER_NAME
                this.context = context
                setOpenTelemetry(sdk)
                start()
            }
            root.addAppender(appender)
            return AutoCloseable {
                root.detachAppender(appender)
                appender.stop()
            }
        }
    }
}
