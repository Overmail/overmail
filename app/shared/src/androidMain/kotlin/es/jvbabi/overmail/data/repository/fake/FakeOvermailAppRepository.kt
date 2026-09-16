package es.jvbabi.overmail.data.repository.fake

import es.jvbabi.overmail.BuildKonfig
import es.jvbabi.overmail.domain.model.AppRelease
import es.jvbabi.overmail.domain.model.Feature
import es.jvbabi.overmail.domain.model.Version
import es.jvbabi.overmail.domain.repository.OvermailAppRepository
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * Stands in for the real releases on GitHub, so the update flow can be walked through on a machine
 * with nothing to update to.
 *
 * Answers instantly in shape but not in time: every call sleeps for about as long as the real one
 * takes, because a prompt that appears fully formed hides exactly the states worth looking at — the
 * spinner in place of the version, the changelog placeholder.
 *
 * Loaded in place of [es.jvbabi.overmail.data.repository.OvermailAppRepositoryImpl] when
 * `app.dev.fake-update` is set in `local.properties`. See `platformModule`.
 */
class FakeOvermailAppRepository : OvermailAppRepository {

    /** The running build, unfaked — the prompt should show what is really installed. */
    override fun getCurrentVersion(): String = BuildKonfig.CURRENT_VERSION

    override suspend fun getLatestVersion(): String {
        delay(REQUEST_DELAY)
        return FAKE_VERSIONS.first()
    }

    /**
     * A URL that is never fetched: [FakeUpdateRepository] is what receives it, and that invents its
     * own progress rather than downloading anything.
     */
    override suspend fun getDownloadLinkForLatestVersion(): String {
        delay(REQUEST_DELAY)
        return "https://example.invalid/Overmail.${FAKE_VERSIONS.first()}.android-universal-release.apk"
    }

    override suspend fun getReleases(): List<AppRelease> {
        delay(REQUEST_DELAY)
        return FAKE_VERSIONS.map { version ->
            // Empty: the fake changelog is picked by version below, not fetched from an asset.
            AppRelease(version = version, changelogAssets = emptyMap())
        }
    }

    override suspend fun getChangelog(release: AppRelease, language: String): Version? {
        delay(REQUEST_DELAY)
        return FAKE_CHANGELOGS[release.version]
    }
}

/** Roughly what a round trip to GitHub costs, so the loading states are actually visible. */
private val REQUEST_DELAY = 600.milliseconds

/**
 * Fake releases, newest first.
 *
 * Dated far enough ahead that they stay newer than whatever build tag the machine produces, and far
 * enough ahead to be unmistakably fake in the UI.
 */
private val FAKE_VERSIONS = listOf(
    "20991231_2359",
    "20990601_0900",
    "20990101_1200",
)

/**
 * One changelog per fake release, deliberately uneven: a full one, one with fixes only, and one with
 * nothing to say at all. That last one has to be dropped rather than shown as an empty section,
 * which is the sort of thing this exists to make visible.
 */
private val FAKE_CHANGELOGS = mapOf(
    FAKE_VERSIONS[0] to Version(
        name = FAKE_VERSIONS[0],
        features = mapOf(
            29 to Feature(
                title = "Install updates in the app",
                description = "Updates are downloaded and installed by the app itself instead of " +
                    "being handed to a browser.",
            ),
            31 to Feature(
                title = "Faked updates",
                description = "The update flow can be walked through without a release to update to.",
            ),
        ),
        bugfixes = mapOf(
            26 to "The update sheet can be dismissed again.",
        ),
        tasks = emptyMap(),
    ),
    FAKE_VERSIONS[1] to Version(
        name = FAKE_VERSIONS[1],
        features = emptyMap(),
        bugfixes = mapOf(
            24 to "Opening an email no longer flickers.",
            25 to "The importer keeps importing after a few hours.",
        ),
        tasks = mapOf(
            23 to "Raised the minimum Android version.",
        ),
    ),
    // Nothing to show, so the changelog must leave it out entirely.
    FAKE_VERSIONS[2] to Version(
        name = FAKE_VERSIONS[2],
        features = emptyMap(),
        bugfixes = emptyMap(),
        tasks = emptyMap(),
    ),
)
