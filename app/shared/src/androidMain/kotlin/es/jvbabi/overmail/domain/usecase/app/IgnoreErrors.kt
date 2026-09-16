package es.jvbabi.overmail.domain.usecase.app

import kotlinx.coroutines.CancellationException

/**
 * Runs [block] and swallows failures. Update checks are strictly best-effort: an unreachable
 * GitHub must never surface as an error to the user.
 */
internal suspend fun <T> ignoreErrors(block: suspend () -> T): T? {
    return try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
