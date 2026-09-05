package es.jvbabi.overmail.server.http.api

import es.jvbabi.overmail.server.database.OvermailDatabase
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.di.dependencies

/**
 * Something out of the container the application was wired in, see `AppModule.kt`.
 *
 * Resolved per call and never at install time: a provider builds its value on first resolution,
 * and the database provider creates the schema while doing it.
 */
suspend inline fun <reified T : Any> ApplicationCall.dependency(): T = application.dependencies.resolve()

/** The database, which every route needs. `query { }` is the only way into it, see AGENTS.md. */
suspend fun ApplicationCall.database(): OvermailDatabase = dependency()
