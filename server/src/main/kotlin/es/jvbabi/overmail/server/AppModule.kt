package es.jvbabi.overmail.server

import es.jvbabi.overmail.server.ai.MailAnalyst
import es.jvbabi.overmail.server.domain.agent.MailClassifier
import es.jvbabi.overmail.server.auth.JwtService
import es.jvbabi.overmail.server.auth.installOvermailAuthentikt
import es.jvbabi.overmail.server.auth.installSessionAuth
import es.jvbabi.overmail.server.config.AiConfig
import es.jvbabi.overmail.server.config.ApplicationConfig
import es.jvbabi.overmail.server.config.SmtpConfig
import es.jvbabi.overmail.server.database.DatabaseConfig
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.changes.PostgresChangeStream
import es.jvbabi.overmail.server.domain.repository.ArchiveRepository
import es.jvbabi.overmail.server.domain.repository.ArchiveRepositoryImpl
import es.jvbabi.overmail.server.domain.repository.EmailAvatarRepository
import es.jvbabi.overmail.server.domain.repository.EmailAvatarRepositoryImpl
import es.jvbabi.overmail.server.domain.repository.EmailRepository
import es.jvbabi.overmail.server.domain.repository.EmailRepositoryImpl
import es.jvbabi.overmail.server.domain.repository.EmailUserRepository
import es.jvbabi.overmail.server.domain.repository.EmailUserRepositoryImpl
import es.jvbabi.overmail.server.domain.repository.ImapAccountRepository
import es.jvbabi.overmail.server.domain.repository.ImapAccountRepositoryImpl
import es.jvbabi.overmail.server.domain.repository.OutgoingMailRepository
import es.jvbabi.overmail.server.domain.repository.OutgoingMailRepositoryImpl
import es.jvbabi.overmail.server.domain.repository.TagRepository
import es.jvbabi.overmail.server.domain.repository.TagRepositoryImpl
import es.jvbabi.overmail.server.domain.repository.SpamFilterRepository
import es.jvbabi.overmail.server.domain.repository.SpamRepository
import es.jvbabi.overmail.server.domain.repository.AiQueueRepository
import es.jvbabi.overmail.server.domain.repository.AiQueueRepositoryImpl
import es.jvbabi.overmail.server.domain.repository.EmailAiClassificationRepository
import es.jvbabi.overmail.server.domain.repository.EmailAiClassificationRepositoryImpl
import es.jvbabi.overmail.server.domain.repository.MagicEmailRepository
import es.jvbabi.overmail.server.domain.repository.MagicEmailRepositoryImpl
import es.jvbabi.overmail.server.domain.repository.MailIdentifierRepository
import es.jvbabi.overmail.server.domain.repository.MailIdentifierRepositoryImpl
import es.jvbabi.overmail.server.domain.repository.MemoryRepository
import es.jvbabi.overmail.server.domain.repository.MemoryRepositoryImpl
import es.jvbabi.overmail.server.domain.repository.SpamRepositoryImpl
import es.jvbabi.overmail.server.domain.repository.SpamFilterRepositoryImpl
import es.jvbabi.overmail.server.domain.repository.ThreadRepository
import es.jvbabi.overmail.server.domain.repository.ThreadRepositoryImpl
import es.jvbabi.overmail.server.domain.repository.UserRepository
import es.jvbabi.overmail.server.domain.repository.UserRepositoryImpl
import es.jvbabi.overmail.server.domain.repository.icon.EmailIconRepository
import es.jvbabi.overmail.server.domain.repository.icon.EmailIconRepositoryImpl
import es.jvbabi.overmail.server.domain.spam.SpamRuleMatcher
import es.jvbabi.overmail.server.http.configureRouting
import es.jvbabi.overmail.server.http.installStatusPages
import es.jvbabi.overmail.server.jobs.avatar.AvatarRefresher
import es.jvbabi.overmail.server.jobs.ai.AiMailProcessor
import es.jvbabi.overmail.server.jobs.ai.AiProcessingState
import es.jvbabi.overmail.server.jobs.importer.ImporterManager
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.resolve
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

/**
 * The Ktor application is the composition root: it owns the object graph and the coroutine scope
 * that the change stream and the importers run in, so stopping the server tears both down.
 */
fun Application.overmail() {
    configureDependencies()
    // Authentikt receives typed request bodies, so this has to be in place before its routes are.
    install(ContentNegotiation) { json() }
    // Before the routes, so what they throw is answered rather than logged as a fault.
    installStatusPages()
    installWebSockets()
    installOvermailAuthentikt()
    // Before the routes: a route cannot ask for an authentication that is not installed yet.
    installSessionAuth()
    configureRouting()
    startImporter()
    startAiProcessor()
}

/**
 * The socket the stack screen runs on, see `http/webapp/mystack`.
 *
 * With a converter, so a handler receives and sends its own command and event types instead of
 * parsing frames. The ping is what keeps a screen that sits idle for minutes connected: a reverse
 * proxy drops a connection nothing travels on, and the reader may well stare at one mail for
 * longer than that.
 */
private fun Application.installWebSockets() {
    install(WebSockets) {
        contentConverter = KotlinxWebsocketSerializationConverter(Json)
        pingPeriod = 15.seconds
        timeout = 30.seconds
    }
}

private fun Application.configureDependencies() {
    dependencies {
        provide<ApplicationConfig> { ApplicationConfig.load() }
        provide<DatabaseConfig> { resolve<ApplicationConfig>().database }
        provide<SmtpConfig> { resolve<ApplicationConfig>().email.smtp }
        provide<AiConfig> { resolve<ApplicationConfig>().ai }

        // Creating the schema on first resolution keeps it in one place; the providers below are
        // the only way to reach the database, so nothing can query it before this ran.
        provide<OvermailDatabase> { OvermailDatabase(resolve()).also { it.init() } }

        provide<PostgresChangeStream> { PostgresChangeStream(resolve(), this@configureDependencies) }

        provide<UserRepository> { UserRepositoryImpl(resolve(), resolve()) }
        provide<ImapAccountRepository> { ImapAccountRepositoryImpl(resolve(), resolve()) }
        provide<EmailUserRepository> { EmailUserRepositoryImpl(resolve(), resolve()) }
        provide<EmailRepository> { EmailRepositoryImpl(resolve(), resolve()) }
        provide<OutgoingMailRepository> { OutgoingMailRepositoryImpl(resolve()) }
        provide<TagRepository> { TagRepositoryImpl(resolve(), resolve()) }
        provide<ThreadRepository> { ThreadRepositoryImpl(resolve(), resolve()) }
        provide<ArchiveRepository> { ArchiveRepositoryImpl(resolve(), resolve()) }
        provide<EmailAvatarRepository> { EmailAvatarRepositoryImpl(resolve(), resolve()) }
        provide<SpamFilterRepository> { SpamFilterRepositoryImpl(resolve(), resolve()) }
        provide<SpamRepository> { SpamRepositoryImpl(resolve(), resolve()) }
        provide<MagicEmailRepository> { MagicEmailRepositoryImpl(resolve(), resolve()) }
        // No change stream: nothing subscribes to these, they are asked for by identifier.
        provide<MailIdentifierRepository> { MailIdentifierRepositoryImpl(resolve()) }
        // Nor here: a stored run is read by whoever opens the mail, not watched.
        provide<EmailAiClassificationRepository> { EmailAiClassificationRepositoryImpl(resolve()) }
        // Nor here: what the mailbox knows about its reader is read when a mail is read.
        provide<MemoryRepository> { MemoryRepositoryImpl(resolve()) }
        // This one does watch: the worker wakes on it and the agent panel counts what is left.
        provide<AiQueueRepository> { AiQueueRepositoryImpl(resolve(), resolve()) }
        // No database of its own: this one only talks to third parties.
        provide<EmailIconRepository> { EmailIconRepositoryImpl() }
        provide<JwtService> { JwtService() }
        // Holds the connection to the model backend, so one per server rather than one per mail.
        provide<MailAnalyst> { MailAnalyst(resolve()) }
        // One of these per server: it holds no state, it is what every caller that wants a mail read
        // goes through -- the detail screen's socket, the queue, and whatever asks next.
        provide<MailClassifier> {
            MailClassifier(
                analyst = resolve(),
                emails = resolve(),
                tagging = resolve(),
                threading = resolve(),
                magic = resolve(),
                matters = resolve(),
                remembering = resolve(),
                classifications = resolve(),
                config = resolve(),
            )
        }
        // Stateless, and the rules it reads come in per request; the coming automatic filing will
        // hold mails against the same one.
        provide<SpamRuleMatcher> { SpamRuleMatcher() }

        // Started from a route rather than on boot: filling the cache is a button, see
        // AvatarRefresher.
        provide<AvatarRefresher> {
            AvatarRefresher(
                emailUserRepository = resolve(),
                avatarRepository = resolve(),
                iconRepository = resolve(),
                coroutineScope = this@configureDependencies,
            )
        }

        provide<ImporterManager> {
            ImporterManager(
                database = resolve(),
                imapAccountRepository = resolve(),
                emailUserRepository = resolve(),
                emailRepository = resolve(),
                aiQueueRepository = resolve(),
                coroutineScope = this@configureDependencies,
            )
        }

        // The mark on the mail the agent has open. Shared between the worker that moves it and the
        // socket that reports it, which is why it is provided rather than owned by either.
        provide<AiProcessingState> { AiProcessingState() }

        provide<AiMailProcessor> {
            AiMailProcessor(
                queue = resolve(),
                emails = resolve(),
                classifier = resolve(),
                state = resolve(),
                coroutineScope = this@configureDependencies,
            )
        }
    }
}

private fun Application.startImporter() {
    launch {
        dependencies.resolve<ImporterManager>().start()
    }
}

/**
 * Starts the walk over the queue.
 *
 * Its own start rather than part of the importer's, because the two are independent: the queue is
 * worked off whether or not any mail is coming in, and mail comes in whether or not the agent is
 * keeping up.
 */
private fun Application.startAiProcessor() {
    launch {
        dependencies.resolve<AiMailProcessor>().start()
    }
}
