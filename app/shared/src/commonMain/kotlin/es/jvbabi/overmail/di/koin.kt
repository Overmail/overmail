package es.jvbabi.overmail.di

import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import es.jvbabi.overmail.BuildKonfig
import es.jvbabi.overmail.data.database.OvermailDatabase
import es.jvbabi.overmail.data.database.converter.ColorConverter
import es.jvbabi.overmail.data.database.converter.InstantConverter
import es.jvbabi.overmail.data.database.converter.UuidConverter
import es.jvbabi.overmail.data.network.installClientDefaults
import es.jvbabi.overmail.data.repository.AccountRepositoryImpl
import es.jvbabi.overmail.data.repository.LabelsRepositoryImpl
import es.jvbabi.overmail.data.repository.ImapAccountsRepositoryImpl
import es.jvbabi.overmail.data.repository.ParticipantsRepositoryImpl
import es.jvbabi.overmail.data.repository.KeyValueRepositoryImpl
import es.jvbabi.overmail.domain.repository.AccountRepository
import es.jvbabi.overmail.domain.repository.LabelsRepository
import es.jvbabi.overmail.domain.repository.ImapAccountsRepository
import es.jvbabi.overmail.domain.repository.ParticipantsRepository
import es.jvbabi.overmail.domain.repository.KeyValueRepository
import es.jvbabi.overmail.page.home.HomeViewModel
import es.jvbabi.overmail.page.home.ViewSettingsViewModel
import es.jvbabi.overmail.page.home.ViewViewModel
import es.jvbabi.overmail.page.onboarding.OnboardingViewModel
import es.jvbabi.overmail.page.onboarding.auth.OnboardingAuthViewModel
import es.jvbabi.overmail.page.onboarding.permissions.OnboardingPermissionsViewModel
import es.jvbabi.overmail.page.onboarding.success.OnboardingSuccessViewModel
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.serialization.json.Json
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.bind
import org.koin.dsl.module
import kotlin.time.Duration.Companion.seconds

expect fun getDatabaseBuilder(): RoomDatabase.Builder<OvermailDatabase>

/**
 * Everything only one platform has, and that therefore cannot be declared in the shared module —
 * the Android in-app updater, for instance, which iOS has no counterpart for.
 *
 * This is for dependencies whose *implementation as well as their interface* is platform-specific.
 * A dependency that the shared code uses through a common interface belongs in the module the
 * platform's entry point (`MainApplication` / `MainViewController`) passes to [initKoin] instead,
 * since only that knows the platform's context.
 */
expect fun platformModule(): Module

/** Qualifier of the [HttpClient] used for requests to hosts we don't control. */
const val KOIN_HTTP_CLIENT_THIRD_PARTY = "http_client_third_party"

private val jsonInstance = Json {
    prettyPrint = true
    isLenient = true
    ignoreUnknownKeys = true
}

fun initKoin(appDeclaration: KoinAppDeclaration = {}) = startKoin {
    appDeclaration()

    modules(module {
        single {
            getDatabaseBuilder()
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .addTypeConverter(UuidConverter())
                .addTypeConverter(InstantConverter())
                .addTypeConverter(ColorConverter())
                .build()
        }

        single<HttpClient> {
            HttpClient {
                installClientDefaults()

                install(ContentNegotiation) {
                    // Keeps ContentNegotiation from trying to (de)serialize the WebSocket session
                    // itself during the WS handshake
                    // ("Serializer for class 'DefaultClientWebSocketSession' is not found").
                    ignoreType<DefaultClientWebSocketSession>()
                    json(jsonInstance)
                }

                install(WebSockets) {
                    contentConverter = KotlinxWebsocketSerializationConverter(jsonInstance)
                    pingIntervalMillis = 10.seconds.inWholeMilliseconds
                }

                defaultRequest {
                    // Lets the server tell which build a request comes from.
                    header("X-App", "Overmail")
                    header("X-App-Version", BuildKonfig.CURRENT_VERSION)

                    // Werkbank answers an unauthenticated request with its login page, which an app
                    // cannot get through. Only ever set on a developer build.
                    if (BuildKonfig.WERKBANK_TOKEN != null) {
                        header("Werkbank-No-Browser", "true")
                        header("Werkbank-Access-Token", BuildKonfig.WERKBANK_TOKEN)
                    }
                }
            }
        }

        // Client for third-party APIs (e.g. GitHub). Deliberately carries no Werkbank headers,
        // so the access token can never leak to a host outside our own infrastructure.
        single<HttpClient>(named(KOIN_HTTP_CLIENT_THIRD_PARTY)) {
            HttpClient {
                installClientDefaults()

                install(ContentNegotiation) {
                    json(jsonInstance)
                }
            }
        }

        singleOf(::KeyValueRepositoryImpl) bind KeyValueRepository::class
        singleOf(::AccountRepositoryImpl) bind AccountRepository::class
        singleOf(::LabelsRepositoryImpl) bind LabelsRepository::class
        singleOf(::ParticipantsRepositoryImpl) bind ParticipantsRepository::class
        singleOf(::ImapAccountsRepositoryImpl) bind ImapAccountsRepository::class

        viewModelOf(::HomeViewModel)
        viewModelOf(::ViewViewModel)
        viewModelOf(::ViewSettingsViewModel)
        viewModelOf(::OnboardingAuthViewModel)
        viewModelOf(::OnboardingPermissionsViewModel)
        viewModelOf(::OnboardingViewModel)
        // Takes the id of the account to greet as a parameter.
        viewModelOf(::OnboardingSuccessViewModel)
    })

    modules(platformModule())
}
