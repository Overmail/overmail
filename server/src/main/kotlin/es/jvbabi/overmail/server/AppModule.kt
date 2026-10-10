package es.jvbabi.overmail.server

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import es.jvbabi.overmail.server.ai.classification.EmailClassification
import es.jvbabi.overmail.server.ai.classification.EmailClassificationQueue
import es.jvbabi.overmail.server.ai.chat.ChatAgent
import es.jvbabi.overmail.server.ai.chat.ChatAgentQueue
import es.jvbabi.overmail.server.auth.JwtService
import es.jvbabi.overmail.server.oauth.OAuthProviders
import es.jvbabi.overmail.server.oauth.OAuthTokens
import es.jvbabi.overmail.server.oauth.installOAuthOnboardings
import es.jvbabi.overmail.server.auth.installOvermailAuthentikt
import es.jvbabi.overmail.server.auth.overmailSession
import es.jvbabi.overmail.server.auth.registerSessionSecurityScheme
import es.jvbabi.overmail.server.config.ApplicationConfig
import org.slf4j.LoggerFactory
import es.jvbabi.overmail.server.config.FirebaseServiceAccount
import es.jvbabi.overmail.server.jobs.push.FcmPushSender
import es.jvbabi.overmail.server.jobs.push.PushNotifications
import es.jvbabi.overmail.server.jobs.push.PushQueue
import es.jvbabi.overmail.server.jobs.push.PushSender
import es.jvbabi.overmail.server.jobs.push.PushTarget
import es.jvbabi.overmail.server.config.SmtpConfig
import es.jvbabi.overmail.server.data.avatar.AvatarLookup
import es.jvbabi.overmail.server.data.knowledge.KnowledgeStore
import es.jvbabi.overmail.server.data.notifier.AiChatNotifier
import es.jvbabi.overmail.server.data.notifier.AiChatStreamNotifier
import es.jvbabi.overmail.server.data.notifier.AvatarNotifier
import es.jvbabi.overmail.server.data.notifier.MailNotifier
import es.jvbabi.overmail.server.data.notifier.ViewNotifier
import es.jvbabi.overmail.server.database.DatabaseConfig
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.http.api.installApiErrorHandling
import es.jvbabi.overmail.server.http.api.installBackendHeaders
import es.jvbabi.overmail.server.http.configureRouting
import es.jvbabi.overmail.server.jobs.avatar.AvatarQueue
import es.jvbabi.overmail.server.jobs.avatar.AvatarShapeBackfill
import es.jvbabi.overmail.server.jobs.importer.ClassifyStep
import es.jvbabi.overmail.server.jobs.importer.EmailImportPipeline
import es.jvbabi.overmail.server.jobs.importer.EmailImportPipelineImpl
import es.jvbabi.overmail.server.jobs.importer.EmailInserter
import es.jvbabi.overmail.server.jobs.importer.EmailInserterImpl
import es.jvbabi.overmail.server.jobs.importer.EmailPreviewGenerator
import es.jvbabi.overmail.server.jobs.importer.EmailPreviewGeneratorImpl
import es.jvbabi.overmail.server.jobs.importer.FolderSynchronizer
import es.jvbabi.overmail.server.jobs.importer.FolderSynchronizerImpl
import es.jvbabi.overmail.server.jobs.importer.GeneratePreviewStep
import es.jvbabi.overmail.server.jobs.importer.ImporterManager
import es.jvbabi.overmail.server.jobs.importer.MailboxImporter
import es.jvbabi.overmail.server.jobs.importer.NotifyStep
import es.jvbabi.overmail.server.jobs.preview.EmailPreviewQueue
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.sse.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import kotlin.time.Duration.Companion.seconds

/**
 * The Ktor application is the composition root: it owns the object graph and the coroutine scope
 * the importers run in, so stopping the server tears them down.
 */
fun Application.overmail() {
    configureDependencies()
    installBackendHeaders()
    // Authentikt receives typed request bodies, so this has to be in place before its routes are.
    install(ContentNegotiation) { json() }
    // Before the routes, so everything they throw comes out as the api's error payload.
    installApiErrorHandling()
    install(Authentication) { overmailSession() }
    registerSessionSecurityScheme()
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
    installOAuthOnboardings()
    configureRouting()
    startJobs()
}

private fun Application.configureDependencies() {
    install(Koin) {
        slf4jLogger()
        modules(overmailModule(this@configureDependencies))
    }
}

/**
 * Everything the server shares. [application] is the coroutine scope the importers run in.
 *
 * Every definition is a `single`, built on first use -- except the database, which is built
 * when the application starts so a server that cannot reach it does not come up at all.
 */
internal fun overmailModule(application: CoroutineScope) = module {
    single<ApplicationConfig> { ApplicationConfig.load() }
    single<DatabaseConfig> { get<ApplicationConfig>().database }
    single<SmtpConfig> { get<ApplicationConfig>().email.smtp }
    single<ApplicationConfig.AiConfig> { get<ApplicationConfig>().ai }

    single<AiChatNotifier> { AiChatNotifier() }
    single<AiChatStreamNotifier> { AiChatStreamNotifier() }
    single<AvatarNotifier> { AvatarNotifier() }
    single<MailNotifier> { MailNotifier() }
    single<ViewNotifier> { ViewNotifier() }

    // Creating the schema with the instance keeps it in one place: every caller reaches the
    // database through this definition, so nothing can query it before this ran.
    single<OvermailDatabase>(createdAtStart = true) {
        OvermailDatabase(get<DatabaseConfig>()).also { runBlocking { it.init() } }
    }

    single<JwtService> { JwtService() }

    single<OAuthProviders> { get<ApplicationConfig>().let { OAuthProviders(it.oauth, it.baseUrl) } }
    single<OAuthTokens> { OAuthTokens(get<OvermailDatabase>(), get<OAuthProviders>()) }

    single {
        val config = get<ApplicationConfig>()
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

    single {
        EmailClassification(
            config = get<ApplicationConfig>(),
            model = get(),
            overmailDatabase = get(),
            mailNotifier = get(),
            knowledgeStore = get(),
        )
    }

    single<EmailClassificationQueue> {
        EmailClassificationQueue(
            emailClassification = get(),
            database = get()
        )
    }

    // One store for what the assistant knows: the chat agent reads and writes it through
    // its tools, the classification reads it into its prompt and writes back what it learned.
    single<KnowledgeStore> { KnowledgeStore(database = get()) }

    single {
        ChatAgent(
            config = get<ApplicationConfig.AiConfig>(),
            model = get(),
            database = get(),
            streamNotifier = get(),
            chatNotifier = get(),
            mailNotifier = get(),
            knowledgeStore = get(),
        )
    }

    single<ChatAgentQueue> { ChatAgentQueue(chatAgent = get(), streamNotifier = get()) }

    // Owns an http client, so one instance rather than one per lookup.
    single<AvatarLookup> { AvatarLookup() }

    single<AvatarQueue> {
        AvatarQueue(
            database = get(),
            avatarLookup = get(),
            avatarNotifier = get(),
            mailNotifier = get(),
        )
    }

    single<AvatarShapeBackfill> { AvatarShapeBackfill(database = get()) }

    single<EmailPreviewQueue> { EmailPreviewQueue(database = get(), emailPreviewGenerator = get()) }

    // Push is optional: without the service account file nothing is sent, and nothing is queued.
    single<PushSender> {
        val serviceAccount = FirebaseServiceAccount.load()
        if (serviceAccount == null) {
            LoggerFactory.getLogger(PushSender::class.java).info("No firebase-service.json, push is switched off")
            PushSender.Disabled
        } else {
            FcmPushSender(serviceAccount)
        }
    }
    single<PushQueue> { PushQueue(database = get(), sender = get()) }
    single<PushNotifications> { PushNotifications(queue = get()) }

    single<EmailInserter> { EmailInserterImpl(database = get()) }
    single<EmailPreviewGenerator> { EmailPreviewGeneratorImpl(database = get()) }

    // What happens to one mail: stored, then everything that follows from that, in this order.
    single<EmailImportPipeline> {
        EmailImportPipelineImpl(
            inserter = get(),
            steps = listOf(
                GeneratePreviewStep(generator = get()),
                ClassifyStep(enqueue = get<EmailClassificationQueue>()::enqueue),
                NotifyStep(mailNotifier = get()),
            ),
        )
    }

    single<FolderSynchronizer> { FolderSynchronizerImpl(database = get(), pipeline = get()) }

    single<ImporterManager> {
        val synchronizer = get<FolderSynchronizer>()
        ImporterManager(database = get(), coroutineScope = application) { connection ->
            MailboxImporter(connection, synchronizer).run()
        }
    }
}

private fun Application.startJobs() {
    launch {
        get<OAuthProviders>().logConfigured()
    }

    launch {
        val importers = get<ImporterManager>()
        get<OAuthTokens>().run(onRenewed = importers::reboot)
    }

    launch {
        get<ImporterManager>().run()
    }

    launch {
        get<EmailClassificationQueue>().consume()
    }

    launch {
        get<AvatarQueue>().consume()
    }

    launch {
        get<AvatarShapeBackfill>().run()
    }

    launch {
        get<EmailPreviewQueue>().consume()
    }

    // After the consumer above, which is what drains what this fills.
    launch {
        get<EmailPreviewQueue>().backfill()
    }

    launch {
        get<ChatAgentQueue>().consume()
    }

    launch {
        get<PushQueue>().consume()
    }

    // For now, while push is being built: every device shows a test notification when the server
    // comes up, which is the quickest way to see that one gets through.
    get<PushNotifications>().ping(PushTarget.Everyone)
}
