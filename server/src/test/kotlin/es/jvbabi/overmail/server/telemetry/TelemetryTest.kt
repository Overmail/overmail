package es.jvbabi.overmail.server.telemetry

import ch.qos.logback.classic.LoggerContext
import com.sun.net.httpserver.HttpServer
import es.jvbabi.overmail.server.config.TelemetryConfig
import io.ktor.client.request.get
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.ktor.v3_0.KtorServerTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** What leaves the server, and that nothing does without a config. */
class TelemetryTest {

    private val rootLogger = (LoggerFactory.getILoggerFactory() as LoggerContext).getLogger(Logger.ROOT_LOGGER_NAME)

    @Test
    fun `traces, metrics and logs are sent to the collector, with the configured headers`() {
        // What the collector was sent: the path of every request, and the credentials it came with.
        val received = ConcurrentHashMap<String, String>()
        val collector = HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
            createContext("/") { exchange ->
                received[exchange.requestURI.path] = exchange.requestHeaders.getFirst("Authorization").orEmpty()
                exchange.requestBody.readAllBytes()
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            start()
        }

        try {
            val telemetry = Telemetry.start(
                TelemetryConfig(
                    // A trailing slash is what a copied endpoint tends to end in.
                    endpoint = "http://localhost:${collector.address.port}/",
                    headers = mapOf("Authorization" to "Bearer secret"),
                )
            )
            val openTelemetry = telemetry.openTelemetry

            runBlocking {
                openTelemetry.getTracer("test").span("a span") {
                    LoggerFactory.getLogger(TelemetryTest::class.java).info("a line")
                }
            }
            openTelemetry.getMeter("test").counterBuilder("a.counter").build().add(1)

            // Nothing was flushed by hand: closing is what sends it.
            telemetry.close()

            assertEquals(
                mapOf("/v1/traces" to "Bearer secret", "/v1/metrics" to "Bearer secret", "/v1/logs" to "Bearer secret"),
                received.toMap(),
            )
            assertEquals(null, rootLogger.getAppender("OTLP"))
        } finally {
            collector.stop(0)
        }
    }

    @Test
    fun `without a config nothing is set up`() {
        val telemetry = Telemetry.start(null)

        assertSame(OpenTelemetry.noop(), telemetry.openTelemetry)
        assertEquals(null, rootLogger.getAppender("OTLP"))
        telemetry.close()
    }

    @Test
    fun `a request is a span named after its route`() = testApplication {
        val spans = InMemorySpanExporter.create()
        val openTelemetry = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
            .build()

        application {
            install(KtorServerTelemetry) { setOpenTelemetry(openTelemetry) }
            routing {
                get("/api/mails/{id}") { call.respondText("a mail") }
            }
        }

        client.get("/api/mails/1")

        assertEquals(listOf("GET /api/mails/{id}"), spans.finishedSpanItems.map { it.name })
    }
}
