plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

group = "edu.sjsu"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val ktorVersion = "3.0.3"

dependencies {
    implementation(project(":data"))
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-sessions:$ktorVersion")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("ch.qos.logback:logback-classic:1.5.12")
}

application {
    mainClass.set("ServerKt")
}

kotlin {
    jvmToolchain(21)
}

tasks.named<JavaExec>("run") {
    dependsOn(":frontend:wasmJsBrowserDevelopmentExecutable")
}
