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

            api(libs.ktor.client.core)
            implementation(libs.app.ktor.client.content.negotiation)
            implementation(libs.app.ktor.client.websockets)
            implementation(libs.ktor.serialization.kotlinx.json)
        }

        iosMain.dependencies {
            implementation(libs.app.ktor.client.darwin)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.app.compose.uiTooling)
}

buildkonfig {
    packageName = "es.jvbabi.overmail"

    defaultConfigs {
        // Where the app looks for the api. Overridable per machine, so a device can be pointed at
        // a laptop's werkbank instance instead of the deployed one.
        buildConfigField(
            type = Type.STRING,
            name = "SERVER_URL",
            value = localProperties["app.server_url"]?.toString() ?: "https://overmail.wb.local",
            nullable = false,
            const = true,
        )
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
    }
}
