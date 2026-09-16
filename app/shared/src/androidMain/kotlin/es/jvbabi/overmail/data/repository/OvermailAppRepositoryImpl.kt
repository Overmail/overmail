package es.jvbabi.overmail.data.repository

import android.os.Build
import es.jvbabi.overmail.BuildKonfig
import es.jvbabi.overmail.domain.model.AppRelease
import es.jvbabi.overmail.domain.model.Feature
import es.jvbabi.overmail.domain.model.GITHUB_REPOSITORY
import es.jvbabi.overmail.domain.model.Version
import es.jvbabi.overmail.domain.repository.OvermailAppRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class OvermailAppRepositoryImpl(
    private val httpClient: HttpClient,
): OvermailAppRepository {
    override suspend fun getLatestVersion(): String? {
        val response = httpClient.get("$GITHUB_API/releases/latest")
        if (!response.status.isSuccess()) return null
        val body = response.body<GitHubRelease>()
        return body.tag.removePrefix(VERSION_TAG_PREFIX)
    }

    /**
     * Picks the APK matching the device's architecture, preferring the device's own ABI order
     * (`Build.SUPPORTED_ABIS` lists the native one first). Falls back to the universal APK when
     * the release ships no split for any supported ABI, and to `null` on platforms that don't
     * install APKs at all.
     */
    override suspend fun getDownloadLinkForLatestVersion(): String? {
        val supportedAbis = Build.SUPPORTED_ABIS.toList()
        if (supportedAbis.isEmpty()) return null

        val response = httpClient.get("$GITHUB_API/releases/latest")
        if (!response.status.isSuccess()) return null
        val assets = response.body<GitHubRelease>().assets.filter { it.name.endsWith(".apk") }

        return (supportedAbis + UNIVERSAL_ABI)
            // Assets are named Overmail.<version>.android-<abi>-release.apk. Matching the whole
            // segment keeps x86 from also matching the x86_64 build.
            .firstNotNullOfOrNull { abi -> assets.firstOrNull { it.name.contains("android-$abi-release") } }
            ?.downloadUrl
    }

    override fun getCurrentVersion(): String {
        return BuildKonfig.CURRENT_VERSION
    }

    override suspend fun getReleases(): List<AppRelease>? {
        // A single page is plenty: the app only ever looks at the releases newer than itself.
        val response = httpClient.get("$GITHUB_API/releases?per_page=$RELEASES_PER_PAGE")
        if (!response.status.isSuccess()) return null

        return response.body<List<GitHubRelease>>()
            .filter { !it.isDraft && !it.isPrerelease }
            .map { release ->
                AppRelease(
                    version = release.tag.removePrefix(VERSION_TAG_PREFIX),
                    changelogAssets = release.assets
                        .filter { it.name.startsWith(CHANGELOG_ASSET_PREFIX) && it.name.endsWith(CHANGELOG_ASSET_SUFFIX) }
                        .associate { it.name to it.downloadUrl },
                )
            }
    }

    /**
     * Changelogs already read, keyed by release and language.
     *
     * The changelog of a published release never changes, so it is worth downloading exactly once:
     * the update check runs again on every return to the foreground, and without this every one of
     * them would re-download every release between the running build and the newest one.
     *
     * A `null` value is a release that ships no changelog, or none this build can read — also a
     * settled answer, and cached so it is not asked again either. Failed downloads are deliberately
     * left out: those are worth another try.
     */
    private val changelogs = mutableMapOf<ChangelogKey, Version?>()

    /** Guards [changelogs], which several update checks may reach for at once. */
    private val changelogsMutex = Mutex()

    override suspend fun getChangelog(release: AppRelease, language: String): Version? =
        changelogsMutex.withLock {
            val key = ChangelogKey(version = release.version, language = language)
            if (key in changelogs) return@withLock changelogs[key]

            val url = release.changelogAssets["$CHANGELOG_ASSET_PREFIX$language$CHANGELOG_ASSET_SUFFIX"]
                ?: release.changelogAssets[CHANGELOG_ASSET_DEFAULT]
                ?: return@withLock null.also { changelogs[key] = it }

            val response = httpClient.get(url)
            if (!response.status.isSuccess()) return@withLock null

            // Read as text and parse explicitly: release assets are served as octet-stream, so
            // content negotiation would refuse them.
            val file = changelogJson.decodeFromString<ChangelogFile>(response.bodyAsText())

            val version = Version(
                name = release.version,
                bugfixes = file.fixes.toDescriptions(),
                features = file.features.toFeatures(),
                tasks = file.tasks.toDescriptions(),
            )

            // A release whose changelog carries nothing usable is treated like one without a
            // changelog at all, so it never shows up as an empty section.
            version.takeUnless { it.isEmpty }.also { changelogs[key] = it }
        }
}

/** What identifies a cached changelog: a release ships one asset per language. */
private data class ChangelogKey(val version: String, val language: String)

/** Name of the ABI-independent APK, usable on every device. */
private const val UNIVERSAL_ABI = "universal"

private const val GITHUB_API = "https://api.github.com/repos/$GITHUB_REPOSITORY"

/** Release tags are the version name prefixed with `v`. */
private const val VERSION_TAG_PREFIX = "v"

private const val CHANGELOG_ASSET_PREFIX = "changelog."
private const val CHANGELOG_ASSET_SUFFIX = ".json"

/** The language-independent changelog, used when there is none for the device's language. */
private const val CHANGELOG_ASSET_DEFAULT = "changelog.json"

private const val RELEASES_PER_PAGE = 100

/**
 * Lenient on purpose: a changelog from a newer release may carry groups or fields this build
 * does not know yet, and that must not cost the user the rest of the entries.
 */
private val changelogJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tag: String,
    @SerialName("assets") val assets: List<Asset>,
    @SerialName("draft") val isDraft: Boolean = false,
    @SerialName("prerelease") val isPrerelease: Boolean = false,
) {
    @Serializable
    data class Asset(
        @SerialName("name") val name: String,
        @SerialName("browser_download_url") val downloadUrl: String
    )
}

/** Shape of the `changelog*.json` assets produced by `.github/generate_changelog.main.kts`. */
@Serializable
private data class ChangelogFile(
    @SerialName("release") val release: String,
    @SerialName("language") val language: String? = null,
    @SerialName("features") val features: Map<String, Entry> = emptyMap(),
    @SerialName("fixes") val fixes: Map<String, Entry> = emptyMap(),
    @SerialName("tasks") val tasks: Map<String, Entry> = emptyMap(),
) {
    @Serializable
    data class Entry(
        @SerialName("title") val title: String? = null,
        @SerialName("description") val description: String? = null,
    )
}

/**
 * Keeps only entries with an issue number as key and a description to show, ordered by issue.
 *
 * Anything else is a changelog that was written wrong or comes from a newer format than this
 * build knows, and dropping just that entry keeps the rest of the release readable.
 */
private fun Map<String, ChangelogFile.Entry>.toDescriptions(): Map<Int, String> = this
    .mapNotNull { (issueKey, entry) ->
        val issue = issueKey.toIntOrNull() ?: return@mapNotNull null
        val description = entry.description?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        issue to description
    }
    .sortedBy { it.first }
    .toMap()

/** Like [toDescriptions], but a feature additionally needs the title it is headlined with. */
private fun Map<String, ChangelogFile.Entry>.toFeatures(): Map<Int, Feature> = this
    .mapNotNull { (issueKey, entry) ->
        val issue = issueKey.toIntOrNull() ?: return@mapNotNull null
        val title = entry.title?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val description = entry.description?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        issue to Feature(title = title, description = description)
    }
    .sortedBy { it.first }
    .toMap()
