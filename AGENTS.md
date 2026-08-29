# AGENTS.md

Kotlin/JVM mail server. Gradle multi-project, currently one module: `server`.
Exposed 1.4 DAO on JDBC/Postgres; every query runs in a suspending transaction on
`Dispatchers.IO`.

## Layout

```
server/src/main/kotlin/es/jvbabi/overmail/server/
  AppModule.kt         Application.overmail(): DI graph, routing, jobs
  Main.kt              starts the server, nothing else
  database/            connection and config; query { } is the only way in
  database/models/     one file per table: the UuidTable object and its UuidEntity
  http/                Ktor engine, config and routes
  jobs/                background work (IMAP import)
```

## Configuration

Everything runtime-specific comes from `data/config.json`, parsed once into `ApplicationConfig`
and handed out through DI. The file holds credentials and is gitignored — never commit it, never
put a credential in a Kotlin default. A missing or malformed file throws with the absolute path
instead of falling back, so nobody ends up on the wrong database.

The path is relative to the working directory, which is why `runServer` sets
`workingDir = rootProject.projectDir`. Add new sections as their own `@Serializable` type
(`DatabaseConfig`, `EmailConfig`) and a field on `ApplicationConfig`.

## Application wiring

The Ktor `Application` is the composition root. Everything is registered in Ktor's own DI
(`ktor-server-di`) in `AppModule.kt`, nothing is configured through an `application.conf`:

```kotlin
dependencies {
    provide<OvermailDatabase> { OvermailDatabase(resolve<DatabaseConfig>()).also { it.init() } }
}
```

- The container is small on purpose: config, the database, `JwtService`, the importer manager.
  Everything else is built where it is used.
- The `Application` doubles as the coroutine scope for the importers, so stopping the server
  tears them down.
- Routes get their dependencies from the same container (`by dependencies` or
  `dependencies.resolve<T>()`); do not connect to the database inside a route.

## Reverse proxy

`deploy/Caddyfile` fronts both halves of the app: `/api*` goes to Ktor, everything else to
SvelteKit in `web/`. Werkbank runs it as a container dependency and points its single `http`
target at it, so the split is identical locally and in a deployed image — werkbank's own traefik
never sees the individual services.

The prefix is **not** stripped, so every Ktor route lives under `route("/api")`. A route added
outside that block is unreachable through the proxy.

Caddy is a werkbank container, so its upstreams are `host.docker.internal`, not `localhost`.

## HTTP and OpenAPI

The spec is assembled from the live routing tree by the Ktor OpenAPI compiler plugin
(`ktor { openApi { } }` in `server/build.gradle.kts`) — there is no checked-in openapi file, and
adding a route is enough to document it. A KDoc comment above a route becomes its summary, so
write one. Swagger UI sits at `/api/swagger`, the generated spec at
`/api/swagger/documentation.json`.

## Authentication

Sign-in is authentikt (`auth/InstallAuthentikt.kt`): identify by username or email, then a code
to the mailbox. It ends in a JWT in the `overmail_session` cookie, issued and verified by
`JwtService` with a secret generated into `data/jwt-secret.key`.

Everything after sign-in goes through the Ktor auth provider in `auth/SessionAuthentication.kt`,
installed unnamed in `AppModule`:

```kotlin
authenticate {
    get("/mails") { call.user }   // the User entity, resolved from the token
}
```

- The token is read from the session cookie or an `Authorization: Bearer` header, verified, and
  the user loaded — no route unpacks a cookie or touches `JwtService` itself.
- `call.user` throws outside a non-optional `authenticate { }`; there is no null case to handle.
- It is a DAO entity from a transaction that is already over: `username`, `email` and `id` are
  readable, everything else needs `query { }` — see [DAO entities](#dao-entities).
- A missing or bad token is a bare 401, no `WWW-Authenticate`: the frontend redirects to /auth,
  a browser credential prompt would be wrong here.

## Database access

Classic Exposed: **the query sits where it is needed.** There is no repository layer and no
interface between a route, a job and the tables -- a handler opens a transaction and writes its
own query.

```kotlin
val database = application.dependencies.resolve<OvermailDatabase>()
val mails = database.query { Email.find { Emails.imapAccount eq accountId }.toList() }
```

- `OvermailDatabase.query { }` is the only entry point. It runs `suspendTransaction` on
  `Dispatchers.IO`, because the JDBC driver blocks the thread it is called on and that thread
  must not be a Netty event loop.
- Reads are plain blocking calls inside that block: `Query` is an `Iterable`, so `map`,
  `firstOrNull` and `toList` are the normal collection functions, not flow operators.
- Nothing observes the database. A read returns the state at that moment; there is no
  subscription and no reload, so anything that has to stay current polls (see `ImporterManager`).

## DAO entities

`database/models/` holds one file per table with the `UuidTable` object **and** its
`UuidEntity`, e.g. `Users` and `User`. The entity is the model -- there is no separate domain
class and no mapper.

```kotlin
class Email(id: EntityID<Uuid>) : UuidEntity(id) {
    companion object : UuidEntityClass<Email>(Emails)

    var subject by Emails.subject
    var sender by EmailUser referencedOn Emails.sender
    val recipients by EmailRecipient referrersOn EmailRecipients.email
}
```

Two things to keep in mind, both of them the price of the DAO:

- **An entity is bound to its transaction.** Its own columns were read with the row and stay
  readable afterwards -- that is what lets the session principal be a `User` (see
  `auth/SessionAuthentication.kt`). A reference, a `referrersOn` collection, a write or
  `refresh()` goes back to the database and throws outside `query { }`. Anything that outlives a
  transaction takes a snapshot instead, like `ImapConnection` in the importer.
- **Loading an entity reads every column**, `Emails.rawContent` included, which is the whole mail
  source. A listing that does not need it selects its columns through the DSL, see
  `http/stack/stackSocket.kt`.

Mixing the DSL into DAO code is fine and sometimes the point: the importer uses
`insertIgnoreAndGetId` for the address it may be inserting concurrently with another account's
importer, because `EmailUser.new` would throw on the unique index instead.

## Conventions

- Table names are plural snake_case (`imap_accounts`, `email_recipients`).
- Comments and identifiers in English, KDoc only where the *why* is non-obvious.
- Register new tables in `OvermailDatabase.init()`, parents before children, and put the entity
  in the same file as the table.

## Verify

```
./gradlew :server:compileKotlin
./gradlew :server:test          # session auth against an in-memory H2, no Postgres needed
./gradlew :server:runServer     # creates the schema, talks to the shared dev DB
curl localhost:8080/api/health
curl localhost:8080/api/swagger/documentation.json
```

To exercise only the HTTP layer, run a throwaway main that starts `embeddedServer(Netty, ...) {
configureRouting() }` via `-PmainClass=...`; that keeps the shared database and the IMAP
importers out of the loop.
