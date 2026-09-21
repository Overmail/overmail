import com.codingfeline.buildkonfig.compiler.FieldSpec.Type
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.buildkonfig)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }

    android {
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        namespace = "es.jvbabi.overmail.shared.compose"

        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }

        androidResources {
            enable = true
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            // What the Xcode project imports, see app/ios/iosApp/ContentView.swift.
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.app.androidx.browser)
            implementation(libs.ktor.client.cio)

            // The QR scanner in onboarding, see QrScanner.android.kt.
            implementation(libs.app.androidx.camera.camera2)
            implementation(libs.app.androidx.camera.lifecycle)
            implementation(libs.app.androidx.camera.view)
            implementation(libs.app.mlkit.barcode.scanning)
        }

        commonMain.dependencies {
            implementation(libs.app.compose.runtime)
            implementation(libs.app.compose.foundation)
            implementation(libs.app.compose.material3)
            implementation(libs.app.compose.ui)
            implementation(libs.app.compose.components.resources)
            implementation(libs.app.compose.uiToolingPreview)
            implementation(libs.app.androidx.lifecycle.viewmodelCompose)
            implementation(libs.app.androidx.lifecycle.runtimeCompose)

            implementation(libs.app.navigation3.runtime)
            implementation(libs.app.navigation3.ui)
            implementation(libs.app.navigation3.lifecycle)

            // api, so :app:android can inject into its own composables.
            api(libs.app.koin.compose)
            implementation(libs.app.koin.compose.navigation3)

            api(libs.app.kermit)

            implementation(libs.kotlinx.datetime)

            implementation(libs.app.androidx.room.runtime)
            implementation(libs.app.androidx.sqlite.bundled)

            implementation(libs.app.haze.blur)
            implementation(libs.app.haze.blur.materials)
            implementation(libs.app.human.readable)

            // Only the weight in use: every weight is a module of its own, and each holds the whole set.
            implementation(libs.app.phosphor.regular)

            // Avatars: loaded through the app's own HttpClient, kept in a disk cache.
            implementation(libs.app.coil.compose)
            implementation(libs.app.coil.network.ktor)

            api(libs.ktor.client.core)
            implementation(libs.app.ktor.client.content.negotiation)
            implementation(libs.app.ktor.client.websockets)
            implementation(libs.app.ktor.client.logging)
            implementation(libs.ktor.serialization.kotlinx.json)

            api(libs.app.moko.permissions.api)
            implementation(libs.app.moko.permissions.compose)
            implementation(libs.app.moko.permissions.camera)
            implementation(libs.app.moko.permissions.notifications)
        }

        iosMain.dependencies {
            implementation(libs.app.ktor.client.darwin)
        }
    }
}

dependencies {
    add("kspAndroid", libs.app.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.app.androidx.room.compiler)
    add("kspIosArm64", libs.app.androidx.room.compiler)
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    androidRuntimeClasspath(libs.app.compose.uiTooling)
}

buildkonfig {
    packageName = "es.jvbabi.overmail"

    defaultConfigs {
        // Werkbank puts a login page in front of every request that does not carry this token,
        // which an app cannot get through. Absent outside a developer machine.
        buildConfigField(
            type = Type.STRING,
            name = "WERKBANK_TOKEN",
            value = localProperties["werkbank.access_token"]?.toString(),
            nullable = true,
        )
        // Defined in the root build script, shared with versionName in :app:android.
        buildConfigField(
            type = Type.STRING,
            name = "CURRENT_VERSION",
            value = rootProject.extra["buildTag"] as String,
            nullable = false,
            const = true,
        )
        // Debug builds carry a throwaway version, so checking them against the latest release
        // only ever nags. Opt in per developer machine to work on the update flow itself.
        buildConfigField(
            type = Type.BOOLEAN,
            name = "CHECK_FOR_UPDATES_IN_DEBUG",
            value = localProperties["app.check_for_updates.enable_in_debug"]
                ?.toString()
                .toBoolean()
                .toString(),
            nullable = false,
            const = true,
        )
        // Logs every request and response with its headers (credentials masked), on by default.
        // A developer machine that finds it too noisy switches it off.
        buildConfigField(
            type = Type.BOOLEAN,
            name = "LOG_HTTP_REQUESTS",
            value = (localProperties["app.dev.log_http_requests"]?.toString()?.toBoolean() ?: true).toString(),
            nullable = false,
            const = true,
        )
        // Swaps the updater's repositories for fake ones, so the whole flow can be walked through
        // without a release to update to — and without spending the 60 requests an hour GitHub
        // allows an unauthenticated client. Opt in per developer machine.
        buildConfigField(
            type = Type.BOOLEAN,
            name = "FAKE_UPDATE",
            value = localProperties["app.dev.fake-update"]
                ?.toString()
                .toBoolean()
                .toString(),
            nullable = false,
            const = true,
        )
    }
}
