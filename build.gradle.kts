import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties
import kotlin.apply

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.buildkonfig) apply false
}

val localProperties = Properties().apply {
    val file = file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

/**
 * Single source of truth for the app version. Consumed by `:app:android` (`versionCode` /
 * `versionName`) and `:app:shared` (`BuildKonfig.CURRENT_VERSION`) so both always agree.
 *
 * `buildTag` is resolved from, in order of precedence:
 * 1. `app.version_name` in `local.properties` — a developer override, e.g. to test the
 *    update check against a specific release.
 * 2. `BUILD_TAG` from the environment — what CI generates once per run.
 * 3. The build time, formatted as `yyyyMMdd_HHmm`.
 *
 * Unless overridden it matches the GitHub release tag of the corresponding build (without the
 * `v` prefix).
 */
val buildTime = System.getenv("BUILD_TIMESTAMP")?.toLongOrNull() ?: (System.currentTimeMillis() / 1000)
val buildTag = localProperties.getProperty("app.version_name")?.takeIf { it.isNotBlank() }
    ?: System.getenv("BUILD_TAG")
    ?: SimpleDateFormat("yyyyMMdd_HHmm").format(Date(buildTime * 1000))

extra["buildTime"] = buildTime
extra["buildTag"] = buildTag
