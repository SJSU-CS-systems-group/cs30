plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
}

group = "edu.sjsu"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val ktorVersion = "3.0.3"

dependencies {
    // Ktor Server
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-sessions:$ktorVersion")
    implementation("io.ktor:ktor-server-html-builder:$ktorVersion")

    // Ktor Client
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")

    // Serialization
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.12")

    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web:3.2.0")
}

application {
    mainClass.set("OAuthScreenKt")
}

kotlin {
    jvmToolchain(21)
}
