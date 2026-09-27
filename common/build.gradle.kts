import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

group = "es.jvbabi.overmail"
version = "unspecified"

repositories {
    mavenCentral()
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }

    android {
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        namespace = "es.jvbabi.overmail.common"

        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    jvm()

    // No framework of its own: :app:shared bundles this into ComposeApp.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // smartDateBoundaries takes a TimeZone, so callers need the types too.
            api(libs.kotlinx.datetime)
        }
    }
}
