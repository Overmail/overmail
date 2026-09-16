package es.jvbabi.overmail.domain.usecase.app

import es.jvbabi.overmail.domain.model.AppVersions
import es.jvbabi.overmail.domain.model.Changelog
import es.jvbabi.overmail.domain.repository.ApplicationRepository
import es.jvbabi.overmail.domain.repository.OvermailAppRepository

class GetReleaseChangelogsUseCase(
    private val overmailAppRepository: OvermailAppRepository,
    private val applicationRepository: ApplicationRepository,
) {
    /**
     * Collects the changelog of every release the user has not seen yet: newer than the running
     * build, up to and including [upToVersion].
     *
     * Versions come newest first. Releases without a readable changelog are left out, so a release
     * from before changelogs existed does not show up as an empty section.
     *
     * Returns `null` when nothing could be fetched or nothing is left to show — this decorates an
     * update prompt and must never keep that prompt from appearing.
     */
    suspend operator fun invoke(upToVersion: String): Changelog? {
        val currentVersion = overmailAppRepository.getCurrentVersion()
        val releases = ignoreErrors { overmailAppRepository.getReleases() } ?: return null
        val language = applicationRepository.language

        val versions = releases
            // The API returns releases newest first, which is the order they are shown in.
            .filter { release ->
                AppVersions.isNewerThan(release.version, currentVersion) &&
                    AppVersions.isAtLeast(upToVersion, release.version)
            }
            .mapNotNull { release ->
                ignoreErrors { overmailAppRepository.getChangelog(release, language) }
            }

        return if (versions.isEmpty()) null else Changelog(versions = versions)
    }
}
