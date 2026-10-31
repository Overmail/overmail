import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    // Turns google-services.json into the resources Firebase configures itself from. The file is
    // gitignored and has to list both application ids, the debug build carries a suffix.
    alias(libs.plugins.google.services)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

kotlin {
    target {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    compilerOptions {
        // authentikt and the compose libraries are published from a pre-release Kotlin, same
        // workaround as in :server.
        freeCompilerArgs.add("-Xskip-prerelease-check")
    }

    dependencies {
        implementation(libs.app.compose.uiToolingPreview)
        implementation(libs.app.androidx.activity.compose)
        implementation(projects.app.shared)
    }
}

android {
    namespace = "es.jvbabi.overmail"

    buildFeatures {
        buildConfig = true
    }

    // One APK per ABI plus a universal one: the per-ABI builds are what a release links to, the
    // universal one is the fallback for anything that cannot tell which it needs.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    if (
        listOf(
            "signing.default.file",
            "signing.default.storepassword",
            "signing.default.keyalias",
            "signing.default.keypassword",
        ).all { localProperties.containsKey(it) }
    ) {
        signingConfigs {
            create("default") {
                storeFile = rootProject.file(localProperties["signing.default.file"]!!)
                storePassword = localProperties["signing.default.storepassword"].toString()
                keyAlias = localProperties["signing.default.keyalias"].toString()
                keyPassword = localProperties["signing.default.keypassword"].toString()
            }
        }
    } else {
        println("Warning: signing configuration not found in local.properties, release builds will not be signed.")
    }

    defaultConfig {
        applicationId = "es.jvbabi.overmail"
        minSdk = libs.versions.android.minSdk.get().toInt()
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()

        // Defined in the root build script, shared with BuildKonfig.CURRENT_VERSION in :app:shared.
        versionCode = (rootProject.extra["buildTime"] as Long).toInt()
        versionName = rootProject.extra["buildTag"] as String
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFile(getDefaultProguardFile("proguard-android-optimize.txt"))
            proguardFile("proguard-rules.pro")
            signingConfig = signingConfigs.findByName("default")
        }

        debug {
            // So a debug build can sit next to an installed release one.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Experimental: html mails rendered by Blitz instead of a web view, see app/html-renderer. Only
// built when a developer machine opts in with app.dev.html_renderer=blitz, which needs rustup with
// the Android targets, cargo-ndk and the NDK; without it the app is built as it always was.
val useBlitz = localProperties["app.dev.html_renderer"]?.toString() == "blitz"
val blitzJniLibs = layout.buildDirectory.dir("generated/blitz/jniLibs")

val buildBlitz = tasks.register<Exec>("buildBlitz") {
    val crate = rootProject.file("app/html-renderer")
    val ndk = localProperties["sdk.dir"]?.let { file(it.toString()).resolve("ndk").listFiles()?.maxByOrNull { dir -> dir.name } }
    workingDir = crate
    inputs.dir(crate.resolve("src"))
    inputs.file(crate.resolve("Cargo.toml"))
    outputs.dir(blitzJniLibs)
    environment("ANDROID_NDK_HOME", ndk?.absolutePath ?: "")
    environment("PATH", "${System.getProperty("user.home")}/.cargo/bin:${System.getenv("PATH")}")
    // rustup installs into ~/.cargo/bin, which a Gradle daemon started from an IDE has no PATH to.
    commandLine(
        "${System.getProperty("user.home")}/.cargo/bin/cargo", "ndk", "-t", "arm64-v8a", "-t", "x86_64", "-P", "26",
        "-o", blitzJniLibs.get().asFile.absolutePath,
        "build", "--release", "--lib",
    )
}

if (useBlitz) {
    android.sourceSets.getByName("main").jniLibs.srcDir(blitzJniLibs.get().asFile)
    tasks.named("preBuild") { dependsOn(buildBlitz) }
}

// AGP builds a JDK image for the compile classpath, and jlink from a JDK newer than this cannot
// read the platform jar of compileSdk 37. Pinning the toolchain keeps the build working with
// whatever JDK Gradle itself runs on -- :server needs 26.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}
