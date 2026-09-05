plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
}

application {
    mainClass.set("es.jvbabi.overmail.server.MainKt")
}

ktor {
    // `buildFatJar` assembles this, which is what the Docker image runs -- see Dockerfile.
    fatJar {
        archiveFileName.set("server-all.jar")
    }

    // Compiler plugin: feeds route metadata (types, KDoc) into the runtime, which is what lets
    // the spec be assembled from the routing tree instead of a hand-written openapi file.
    openApi {
        enabled = true
    }
}

group = "es.jvbabi.overmail"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    // The DAO entities. JDBC only -- this is what the driver below is here for.
    implementation(libs.exposed.dao)
    implementation(libs.exposed.json)
    implementation(libs.exposed.kotlin.datetime)
    implementation(libs.kotlinx.datetime)
    implementation(libs.postgresql)

    // BIMI records are DNS TXT lookups, which the JDK offers no supported API for.
    implementation(libs.dnsjava)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    // Chat answers stream to the browser over EventSource, not over the chat socket.
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.routing.openapi)
    implementation(libs.ktor.server.swagger)
    implementation(libs.ktor.server.di)
    // One error shape for the whole api, see http/api/ApiErrorHandling.kt.
    implementation(libs.ktor.server.status.pages)
    // Authentikt calls call.receive<T>() in its built-in plugins, so this is not optional.
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Downloading avatars. CIO is the engine authentikt already pulls in, so no second one ends
    // up on the classpath.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)

    implementation(libs.authentikt.core)
    implementation(libs.java.jwt)
    implementation(libs.kotlinx.serialization.json)
    // Eclipse Angus, the Jakarta Mail implementation. Kamel already pulls it, but only at
    // runtime scope, so it has to be declared here to compile against jakarta.mail.
    implementation(libs.angus.mail)
    // Ktor logs through slf4j; without a binding it stays silent and warns on startup.
    implementation(libs.logback.classic)

    implementation(libs.kamel)

    // HTML-to-text for mails that ship no text/plain part, see HtmlToText.
    implementation(libs.jsoup)

    // Rasterising an avatar to decide whether it survives a circle, see CircleFit. Batik is for
    // the SVGs, which is what BIMI serves and most of the hand-kept logos are; the two ImageIO
    // plugins add the formats the JDK has no reader for -- webp, and the .ico favicons.
    implementation(libs.batik.transcoder)
    // The image codecs Batik needs for an SVG that embeds a raster image.
    implementation(libs.batik.codec)
    implementation(libs.imageio.webp)
    implementation(libs.imageio.bmp)

    testImplementation(kotlin("test"))
    // Drives routes through the real plugin pipeline without binding a port.
    testImplementation(libs.ktor.server.test.host)
    // In-memory database for tests that need real rows; the schema is created per test class.
    testImplementation(libs.h2)
    testImplementation(libs.kotlinx.coroutines.test)

    implementation(libs.koog.agents)
}

kotlin {
    jvmToolchain(26)
    compilerOptions {
        // authentikt is published from a pre-release Kotlin. Same workaround as in Trails.
        freeCompilerArgs.add("-Xskip-prerelease-check")
    }
}

tasks.test {
    useJUnitPlatform()
    // See Main.kt: without this the avatar tests bounce a Java icon into the macOS dock.
    jvmArgs("-Djava.awt.headless=true")
}
tasks.register<JavaExec>("runServer") {
    group = "application"
    mainClass.set(providers.gradleProperty("mainClass").orElse("es.jvbabi.overmail.server.MainKt"))
    classpath = sourceSets["main"].runtimeClasspath
    // The server reads ./data/config.json, which lives at the repository root, not in this module.
    workingDir = rootProject.projectDir
    jvmArgs("-Djava.awt.headless=true")
}
