package es.jvbabi.overmail.server.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The `telemetry` section of `data/config.json`. Without it nothing is exported. */
@Serializable
data class TelemetryConfig(
    /**
     * Where the collector takes OTLP over http, without the path of a signal:
     * `http://localhost:4318`. Traces, metrics and logs go to `/v1/traces`, `/v1/metrics` and
     * `/v1/logs` below it.
     */
    @SerialName("endpoint") val endpoint: String,

    /** Sent with every export, which is where a hosted collector expects its credentials. */
    @SerialName("headers") val headers: Map<String, String> = emptyMap(),

    @SerialName("service_name") val serviceName: String = "overmail",

    /** Resource attributes next to the service name, `deployment.environment.name` for one. */
    @SerialName("resource") val resource: Map<String, String> = emptyMap(),
)
