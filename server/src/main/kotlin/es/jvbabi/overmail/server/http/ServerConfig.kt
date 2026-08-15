package es.jvbabi.overmail.server.http

data class ServerConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
)
