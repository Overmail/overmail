package es.jvbabi.overmail.server.http.api

import es.jvbabi.overmail.server.database.OvermailDatabase
import io.ktor.server.application.ApplicationCall
import org.koin.ktor.ext.get

/** Something out of the Koin container the application was wired in, see `AppModule.kt`. */
inline fun <reified T : Any> ApplicationCall.dependency(): T = application.get()

/** The database, which every route needs. `query { }` is the only way into it, see AGENTS.md. */
fun ApplicationCall.database(): OvermailDatabase = dependency()
