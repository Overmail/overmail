package es.jvbabi.overmail.server

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import es.jvbabi.overmail.server.ai.classification.EmailClassification
import es.jvbabi.overmail.server.ai.classification.EmailClassificationQueue
import es.jvbabi.overmail.server.auth.JwtService
import es.jvbabi.overmail.server.auth.installOvermailAuthentikt
import es.jvbabi.overmail.server.auth.overmailSession
import es.jvbabi.overmail.server.config.ApplicationConfig
import es.jvbabi.overmail.server.config.SmtpConfig
import es.jvbabi.overmail.server.data.avatar.AvatarLookup
import es.jvbabi.overmail.server.data.notifier.AiChatNotifier
import es.jvbabi.overmail.server.data.notifier.AiChatStreamNotifier
import es.jvbabi.overmail.server.data.notifier.AvatarNotifier
import es.jvbabi.overmail.server.data.notifier.EmailLabelNotifier
import es.jvbabi.overmail.server.database.DatabaseConfig
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.http.configureRouting
import es.jvbabi.overmail.server.jobs.avatar.AvatarQueue
import es.jvbabi.overmail.server.jobs.importer.ImporterManager
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.di.*
import io.ktor.server.sse.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

/**
 * The Ktor application is the composition root: it owns the object graph and the coroutine scope
 * the importers run in, so stopping the server tears them down.
 */
fun Application.overmail() {
    configureDependencies()
    // Authentikt receives typed request bodies, so this has to be in place before its routes are.
    install(ContentNegotiation) { json() }
    install(Authentication) { overmailSession() }
    install(SSE)
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
        contentConverter = KotlinxWebsocketSerializationConverter(Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }
    installOvermailAuthentikt()
    configureRouting()
    startJobs()
}

private fun Application.configureDependencies() {
    dependencies {
        provide<ApplicationConfig> { ApplicationConfig.load() }
        provide<DatabaseConfig> { resolve<ApplicationConfig>().database }
        provide<SmtpConfig> { resolve<ApplicationConfig>().email.smtp }

        provide<EmailLabelNotifier> { EmailLabelNotifier() }
        provide<AiChatNotifier> { AiChatNotifier() }
        provide<AiChatStreamNotifier> { AiChatStreamNotifier() }
        provide<AvatarNotifier> { AvatarNotifier() }

        // Creating the schema on first resolution keeps it in one place: every caller reaches
        // the database through this provider, so nothing can query it before this ran.
        provide<OvermailDatabase> { OvermailDatabase(resolve<DatabaseConfig>()).also { it.init() } }

        provide<JwtService> { JwtService() }

        provide {
            val config = resolve<ApplicationConfig>()
            // The provider must be LLMProvider.OpenAI: MultiLLMPromptExecutor routes requests by
            // comparing the model's provider with the one the registered client reports, and
            // OpenAILLMClient reports LLMProvider.OpenAI regardless of its base URL.
            // OpenAIEndpoint.Completions is required: without it the client cannot decide
            // between the Chat-Completions and the Responses API and refuses the request.
            // Baseten only offers the Chat-Completions endpoint. No Schema capability, so
            // executeStructured embeds the JSON schema and examples into the prompt (manual
            // mode), which works regardless of what the served model supports.
            LLModel(
                provider = LLMProvider.OpenAI,
                id = config.ai.model,
                capabilities = listOf(
                    LLMCapability.OpenAIEndpoint.Completions,
                    LLMCapability.Completion,
                    LLMCapability.Temperature,
                    LLMCapability.Tools,
                ),
            )
        }

        provide {
            EmailClassification(
                config = resolve<ApplicationConfig>(),
                model = resolve(),
                overmailDatabase = resolve(),
                emailLabelNotifier = resolve(),
            )
        }

        provide<EmailClassificationQueue> {
            EmailClassificationQueue(
                emailClassification = resolve(),
                database = resolve()
            )
        }

        // Owns an http client, so one instance rather than one per lookup.
        provide<AvatarLookup> { AvatarLookup() }

        provide<AvatarQueue> {
            AvatarQueue(
                database = resolve(),
                avatarLookup = resolve(),
                avatarNotifier = resolve(),
            )
        }

        provide<ImporterManager> {
            ImporterManager(
                database = resolve(),
                coroutineScope = this@configureDependencies,
                emailClassificationQueue = resolve()
            )
        }
    }
}

private fun Application.startJobs() {
    launch {
        dependencies.resolve<ImporterManager>().start()
    }

    launch {
        dependencies.resolve<EmailClassificationQueue>().consume()
    }

    launch {
        dependencies.resolve<AvatarQueue>().consume()
    }
}
