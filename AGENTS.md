# AGENTS.md

Kotlin/JVM mail server. Gradle multi-project, currently one module: `server`.
Exposed 1.4 on R2DBC/Postgres, coroutines everywhere — no blocking JDBC.

## Layout

```
server/src/main/kotlin/es/jvbabi/overmail/server/
  database/models/     Exposed table objects (UuidTable)
  database/mappers/    ResultRow -> domain model extensions
  database/changes/    PostgresChangeStream (logical replication)
  domain/models/       plain data classes, no Exposed types
  domain/repository/   repository interface + Impl per aggregate
  jobs/                background work (IMAP import)
```

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
```
