package es.jvbabi.overmail.server.auth

import es.jvbabi.overmail.server.config.ApplicationConfig
import es.jvbabi.overmail.server.config.SmtpConfig
import es.jvbabi.overmail.server.domain.models.OutgoingMail
import es.jvbabi.overmail.server.domain.models.User
import es.jvbabi.overmail.server.domain.repository.OutgoingMailRepository
import es.jvbabi.overmail.server.domain.repository.UserRepository
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.resolve
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.uuid.Uuid

private val TESTER = User(
    id = Uuid.parse("00000000-0000-4000-8000-000000000001"),
    username = "tester",
    email = "tester@example.invalid",
    name = "Tester",
)

private class FakeUserRepository : UserRepository {
    override fun getById(id: Uuid): Flow<User?> = flowOf(TESTER.takeIf { it.id == id })
    override fun findByIdentifier(identifier: String): Flow<User?> =
        flowOf(TESTER.takeIf { identifier == it.username || identifier == it.email })
}

private class FakeOutgoingMailRepository : OutgoingMailRepository {
    override suspend fun send(mail: OutgoingMail) {
        println("FAKE-SMTP to=${mail.to} subject=${mail.subject}")
    }
}

fun main() {
    runBlocking {
        embeddedServer(Netty, port = 8099) {
            dependencies {
                provide<ApplicationConfig> { ApplicationConfig.load() }
                provide<SmtpConfig> { resolve<ApplicationConfig>().email.smtp }
                provide<UserRepository> { FakeUserRepository() }
                provide<OutgoingMailRepository> { FakeOutgoingMailRepository() }
                provide<JwtService> { JwtService() }
            }
            install(ContentNegotiation) { json() }
            installOvermailAuthentikt()
        }.startSuspend(wait = true)
    }
}
