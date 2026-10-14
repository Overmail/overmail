package es.jvbabi.overmail.server.http

import es.jvbabi.overmail.server.ai.chat.FakeAiConfig

data class ServerConfig(
    val host: String = "0.0.0.0",
    val port: Int = 30137,
    /** Set by `--fake-ai`: the assistant replays a recorded answer instead of asking the model. */
    val fakeAi: FakeAiConfig? = null,
)
