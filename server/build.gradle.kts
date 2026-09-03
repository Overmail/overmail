plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.4.10"
    id("io.ktor.plugin") version "3.5.2"
}

ktor {
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

    implementation("org.jetbrains.exposed:exposed-core:1.5.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:1.5.0")
    // The DAO entities. JDBC only -- this is what the driver below is here for.
    implementation("org.jetbrains.exposed:exposed-dao:1.5.0")
    implementation("org.jetbrains.exposed:exposed-json:1.5.0")
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:1.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
    implementation("org.postgresql:postgresql:42.7.13")

    // BIMI records are DNS TXT lookups, which the JDK offers no supported API for.
    implementation("dnsjava:dnsjava:3.6.5")

    implementation("io.ktor:ktor-server-core:3.5.2")
    implementation("io.ktor:ktor-server-netty:3.5.2")
    implementation("io.ktor:ktor-server-websockets:3.5.2")
    // Chat answers stream to the browser over EventSource, not over the chat socket.
    implementation("io.ktor:ktor-server-sse:3.5.2")
    implementation("io.ktor:ktor-server-auth:3.5.2")
    implementation("io.ktor:ktor-server-routing-openapi:3.5.2")
    implementation("io.ktor:ktor-server-swagger:3.5.2")
    implementation("io.ktor:ktor-server-di:3.5.2")
    // Authentikt calls call.receive<T>() in its built-in plugins, so this is not optional.
    implementation("io.ktor:ktor-server-content-negotiation:3.5.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.2")

    // Downloading avatars. CIO is the engine authentikt already pulls in, so no second one ends
    // up on the classpath.
    implementation("io.ktor:ktor-client-core:3.5.2")
    implementation("io.ktor:ktor-client-cio:3.5.2")

    implementation("es.jvbabi.authentikt:core:0.4.5")
    implementation("com.auth0:java-jwt:4.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // Eclipse Angus, the Jakarta Mail implementation. Kamel already pulls it, but only at
    // runtime scope, so it has to be declared here to compile against jakarta.mail.
    implementation("org.eclipse.angus:jakarta.mail:2.0.5")
    // Ktor logs through slf4j; without a binding it stays silent and warns on startup.
    implementation("ch.qos.logback:logback-classic:1.5.20")

    implementation("es.jvbabi.overmail:kamel:0.4.0")

    // HTML-to-text for mails that ship no text/plain part, see HtmlToText.
    implementation("org.jsoup:jsoup:1.21.1")

    testImplementation(kotlin("test"))
    // Drives routes through the real plugin pipeline without binding a port.
    testImplementation("io.ktor:ktor-server-test-host:3.5.2")
    // In-memory database for tests that need real rows; the schema is created per test class.
    testImplementation("com.h2database:h2:2.4.240")

    implementation("ai.koog:koog-agents:1.2.0")
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
}
tasks.register<JavaExec>("runServer") {
    group = "application"
    mainClass.set(providers.gradleProperty("mainClass").orElse("es.jvbabi.overmail.server.MainKt"))
    classpath = sourceSets["main"].runtimeClasspath
    // The server reads ./data/config.json, which lives at the repository root, not in this module.
    workingDir = rootProject.projectDir
}
