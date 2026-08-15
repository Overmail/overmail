# AGENTS.md

Kotlin/JVM mail server. Gradle multi-project, currently one module: `server`.
Exposed 1.4 on R2DBC/Postgres, coroutines everywhere — no blocking JDBC.

## Layout

```
server/src/main/kotlin/es/jvbabi/overmail/server/
  AppModule.kt         Application.overmail(): DI graph, routing, jobs
  Main.kt              starts the server, nothing else
  database/models/     Exposed table objects (UuidTable)
  database/mappers/    ResultRow -> domain model extensions
  database/changes/    PostgresChangeStream (logical replication)
  domain/models/       plain data classes, no Exposed types
  domain/repository/   repository interface + Impl per aggregate
  http/                Ktor engine, config and routes
  jobs/                background work (IMAP import)
```

## Application wiring

The Ktor `Application` is the composition root. Everything is registered in Ktor's own DI
(`ktor-server-di`) in `AppModule.kt`, nothing is configured through an `application.conf`:

```kotlin
dependencies {
    provide<EmailRepository> { EmailRepositoryImpl(resolve(), resolve()) }
}
```

- Register the interface, never the `Impl`, so consumers cannot depend on the concrete type.
- The `Application` doubles as the coroutine scope for the change stream and the importers, so
  stopping the server tears them down.
- Routes get their dependencies from the same container (`by dependencies` or
  `dependencies.resolve<T>()`); do not construct repositories inside a route.

## HTTP and OpenAPI

The spec is assembled from the live routing tree by the Ktor OpenAPI compiler plugin
(`ktor { openApi { } }` in `server/build.gradle.kts`) — there is no checked-in openapi file, and
adding a route is enough to document it. A KDoc comment above a route becomes its summary, so
write one. Swagger UI sits at `/swagger`, the generated spec at `/swagger/documentation.json`.

## Database access

**All database access goes through a repository.** Nothing outside
`domain/repository/` may touch an Exposed table, build a query or call
`OvermailDatabase.query {}`. Jobs, services and future API handlers depend on the
repository interface, never on the `Impl`.

**Every reading repository function returns a `Flow`.** Also the ones that look
like a one-shot lookup — callers use `.first()` if they only want the current
value. Build the flow off `PostgresChangeStream.changesOf(...)` so it re-emits
whenever a relevant table changes:

```kotlin
override fun getById(id: Uuid): Flow<Thing?> {
    return changes.changesOf(Things)
        .conflate()
        .map { database.query { /* query, map via database/mappers */ } }
        .distinctUntilChanged()
}
```

- List `changesOf(...)` with *every* table the query reads, joins included.
- `conflate()` before the query, so a burst of WAL events collapses into one read.
- `distinctUntilChanged()` after it, so subscribers only see real changes. This
  needs the domain model to have a working `equals` — keep `ByteArray` out of
  data classes, expose blobs through their own accessor instead.

**Writing functions are `suspend` and return the affected model**, not a `Flow`.
A cold flow would run the write again on every collection. Subscribers pick the
change up through their own flow anyway, because it lands in the WAL.

## Domain models

Data classes hold resolved references (`ImapAccount`, not `imapAccountId`). The
mapper takes them as parameters and the repository resolves them; see
`ImapAccountMapper`. Domain models never expose `EntityID`, `ResultRow` or any
other Exposed type.

## Conventions

- Table names are plural snake_case (`imap_accounts`, `email_recipients`).
- Comments and identifiers in English, KDoc only where the *why* is non-obvious.
- Register new tables in `OvermailDatabase.init()`, parents before children.

## Verify

```
./gradlew :server:compileKotlin
./gradlew :server:runServer     # creates the schema, talks to the shared dev DB
curl localhost:8080/health
curl localhost:8080/swagger/documentation.json
```

To exercise only the HTTP layer, run a throwaway main that starts `embeddedServer(Netty, ...) {
configureRouting() }` via `-PmainClass=...`; that keeps the shared database and the IMAP
importers out of the loop.
