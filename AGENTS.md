# AGENTS.md

Kotlin/JVM mail server with a SvelteKit web app and a Compose Multiplatform app.
Gradle multi-project: `server`, `app:shared`, `app:android`; the web app is bun/vite
in `web/` and outside Gradle. Exposed 1.4 DAO on JDBC/Postgres; every query runs in
a suspending transaction on `Dispatchers.IO`.

## Layout

```
server/src/main/kotlin/es/jvbabi/overmail/server/
  AppModule.kt         Application.overmail(): DI graph, routing, jobs
  Main.kt              starts the server, nothing else
  database/            connection and config; query { } is the only way in
  database/models/     one file per table: the UuidTable object and its UuidEntity
  http/                Ktor engine, config and routes
  http/api/            what every route needs: the current user, url resources, errors
  jobs/                background work (IMAP import)

app/
  shared/              Compose Multiplatform: everything the app is, Android + iOS
  android/             the Android application: MainApplication, MainActivity, manifest, icons
  ios/                 the Xcode project; it builds the ComposeApp framework from :app:shared
```

## App

`app:shared` holds the app itself and is the only place features are written; the two platform
modules are entry points and nothing else.

- **DI is Koin**, started from the platform entry point (`MainApplication` on Android,
  `MainViewController` on iOS) so it can pass in what only that platform knows. `di/koin.kt`
  declares everything shared; `platformModule()` is for dependencies whose interface *and*
  implementation are platform-specific.
- **Navigation is Navigation3**: the back stack is a `SnapshotStateList<Screen>` in `App.kt`,
  pushing navigates and popping goes back. `Screen` is a serializable sealed class.
- **Platform glue** is `expect`/`actual` on top-level functions in `App.kt` (`openUrl`,
  `shareUrl`, `getClipboardText`, `dynamicTheme`) rather than an interface per platform.
- **Build-time values come from BuildKonfig** (`app/shared/build.gradle.kts`): `SERVER_URL`,
  the werkbank token and `CURRENT_VERSION`. The version is defined once in the root build script
  and shared with `versionCode`/`versionName` in `:app:android`.
- `local.properties` holds everything machine-specific: `sdk.dir`, `werkbank.access_token`,
  `app.server_url` and the `signing.default.*` keystore entries. Gitignored, never commit it.
- The Android module pins a JDK 21 toolchain: AGP's JDK image transform cannot be built by a
  jlink newer than the compile SDK, and `:server` needs JDK 26.

## Release pipeline

A merge to `main` runs [deploy.yaml](.github/workflows/deploy.yaml), which builds only what the
merged pull request's `project:*` labels say it touches:

| Label                               | Effect on a merge to `main`                  |
|-------------------------------------|----------------------------------------------|
| `project:app`                       | builds the APKs and publishes a GitHub release |
| `project:server` / `project:webapp` | builds and pushes `ghcr.io/overmail/overmail:latest` |
| none                                | builds everything, with a notice               |

Labelling the issue or the pull request is enough:
[sync-labels.yaml](.github/workflows/sync-labels.yaml) copies every `project:*` label between the
two in both directions.

The image runs all three processes behind one port — the Ktor jar, the SvelteKit server and a
Caddy with the same `/api*` split as locally, see `deploy/production/`. It reads `/data/config.json`,
which is why `OVERMAIL_STORAGE_DIRECTORY` exists (`config/StorageDirectory.kt`).

### Changelog

Every feature branch needs a changelog entry for the issue it closes, in
`docs/changelog/issues/<issue ID>/changelog.<type>.json` — copy the matching file from
`docs/changelog/issues/_template/`. `<type>` is the GitHub issue type: `feature` (needs `title`
and `description`), `bug` or `task` (`description` only, no `title`). Texts are English at the
top level, German under `localized.de`.

`.github/check_changelog.main.kts` checks this on every pull request;
`.github/generate_changelog.main.kts` renders the release body from the entries of every issue
referenced by the commits since the last release. **Only issues labelled `project:app` make it
into the changelog** — a release ships the app, and the app is what reads the changelog.

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
installed unnamed in `AppModule`. Every route function wraps its handlers in `authenticate { }`
itself; a handler then asks the call who is there:

```kotlin
fun Route.setEmailRead() {
    authenticate {
        post { val user = call.requireAuthenticatedUser() }
    }
}
```

- The token is read from the session cookie or an `Authorization: Bearer` header, verified, and
  the user loaded — no route unpacks a cookie or touches `JwtService` itself.
- `requireAuthenticatedUser()` either answers or ends the request with 401, so no handler carries
  a null case. Ask as often as you like: the user is resolved once per request and kept on
  `call.attributes` (`auth/CurrentUser.kt`), the provider's principal included.
- It also resolves the token on its own, so it still works in a route mounted outside
  `authenticate { }` — which is what makes the 401 above reachable at all.
- It is a DAO entity from a transaction that is already over: `username`, `email` and `id` are
  readable, everything else needs `query { }` — see [DAO entities](#dao-entities).
- 401 and no `WWW-Authenticate`: the frontend redirects to /auth on that status, and a browser
  credential prompt would be wrong here. 403 means something else — see below.

## The api layer

`http/api/` holds what every route needs, so a handler is its query and its answer and nothing
else. Reach for these before writing the check by hand:

- **`requireAuthenticatedUser()` / `requireAuthenticatedUserId()`** — who is calling, or 401.
- **`require<Resource>FromUrl()`** — the resource a path parameter points at, or 404. A malformed
  id, an unknown one and a deleted one are the same miss.
- **`requireOwned<Resource>FromUrl()`** — that one, and 403 when it belongs to somebody else.
  Existence and owner come out of one query, and both are cached on the call, so a handler that
  asks twice pays once. `requireOwnedEmailIdFromUrl()` is the columns-only variant for writes that
  touch one flag — the entity reads `Emails.rawContent` with it.
- **`queryParameter` / `intQueryParameter` / `instantQueryParameter` / `uuidQueryParameter`** —
  `?name=value`, or 400 naming the parameter.
- **`database()` / `dependency<T>()`** — out of the DI container, per call.

Errors all leave as the same json, `ApiErrorBody`:

```json
{"error": {"status": 403, "code": "forbidden", "message": "...", "details": {"resource": "email"}}}
```

`ApiException` (thrown through `notFound()`, `forbidden()`, `invalidRequest()`, `conflict()`,
`unauthenticated()`) is turned into that by `installApiErrorHandling()`, which also covers
unmatched routes, unreadable bodies and anything that escapes a handler. A test that mounts a
route on its own `testApplication` installs it too, or an `ApiException` shows up as a bare 500.

Two things the OpenAPI compiler plugin forces on this package, both of them build failures rather
than warnings when ignored: nothing a route handler reaches may be **generic**, and a helper it
reaches must not be called several times from the same handler. That is why the resolvers are
written out per resource instead of sharing one lookup.

## Database access

Classic Exposed: **the query sits where it is needed.** There is no repository layer and no
interface between a route, a job and the tables -- a handler opens a transaction and writes its
own query.

```kotlin
val mails = call.database().query { Email.find { Emails.imapAccount eq accountId }.toList() }
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
### General
- Comments and identifiers in English, KDoc only where the *why* is non-obvious.

### Backend
- Table names are plural snake_case (`imap_accounts`, `email_recipients`).
- Register new tables in `OvermailDatabase.init()`, parents before children, and put the entity
  in the same file as the table.

### Frontend
- Always use Bun instead of npm, yarn etc.
- Use svelte-i18n for all user-facing text in the ui. Use the correct plurals. Add english (fallback) and german. Nest objects by route, e.g. `auth.signin.title`, `auth.signin.button`.
- Catalogs are `web/src/lib/i18n/locales/{en,de}.json`; the locale is decided in `+layout.server.ts`
  (cookie, else `Accept-Language`) and applied in `+layout.ts` before anything renders, so ssr and
  hydration agree. Plurals are ICU: `{count, plural, one {# mail} other {# mails}}`.

## Verify

```
./gradlew :server:compileKotlin
./gradlew :server:test          # session auth against an in-memory H2, no Postgres needed
./gradlew :server:runServer     # creates the schema, talks to the shared dev DB
curl localhost:8080/api/health
curl localhost:8080/api/swagger/documentation.json
```

For the app:

```
./gradlew :app:android:assembleDebug              # Android
./gradlew :app:shared:compileKotlinIosSimulatorArm64   # iOS, without Xcode
./gradlew :server:buildFatJar                     # what the Docker image runs
```

To exercise only the HTTP layer, run a throwaway main that starts `embeddedServer(Netty, ...) {
configureRouting() }` via `-PmainClass=...`; that keeps the shared database and the IMAP
importers out of the loop.
