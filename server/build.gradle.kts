plugins {
    kotlin("jvm")
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

    implementation("org.jetbrains.exposed:exposed-core:1.4.0")
    implementation("org.jetbrains.exposed:exposed-r2dbc:1.4.0")
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:1.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
    implementation("org.postgresql:r2dbc-postgresql:1.1.1.RELEASE")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactive:1.11.0")

    implementation("io.ktor:ktor-server-core:3.5.2")
    implementation("io.ktor:ktor-server-netty:3.5.2")
    implementation("io.ktor:ktor-server-routing-openapi:3.5.2")
    implementation("io.ktor:ktor-server-swagger:3.5.2")
    implementation("io.ktor:ktor-server-di:3.5.2")
    // Ktor logs through slf4j; without a binding it stays silent and warns on startup.
    implementation("ch.qos.logback:logback-classic:1.5.20")

    implementation("es.jvbabi.overmail:kamel:0.4.0")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(26)
}

tasks.test {
    useJUnitPlatform()
}
tasks.register<JavaExec>("runServer") {
    group = "application"
    mainClass.set(providers.gradleProperty("mainClass").orElse("es.jvbabi.overmail.server.MainKt"))
    classpath = sourceSets["main"].runtimeClasspath
}
