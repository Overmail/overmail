package es.jvbabi.overmail.di

import es.jvbabi.overmail.BuildKonfig
import es.jvbabi.overmail.data.remote.OvermailApi
import es.jvbabi.overmail.page.home.HomeViewModel
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module
import kotlin.time.Duration.Companion.seconds

/**
 * Everything only one platform has, and that therefore cannot be declared in the shared module.
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
        single<HttpClient> {
            HttpClient {
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
                install(ContentNegotiation) {
                    json(jsonInstance)
                }
            }
        }

        singleOf(::OvermailApi)

        viewModelOf(::HomeViewModel)
    })

    modules(platformModule())
}
