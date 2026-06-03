plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    kotlin("plugin.spring")
    application
}

group = "edu.sjsu"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val ktorVersion = "3.0.3"

dependencies {
    // Shared data models (labx.data.*)
    implementation(project(":data"))

    // Ktor Server
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-sessions:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-server-html-builder:$ktorVersion")

    // Ktor Client
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")

    // Serialization
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.12")

    // CLI
    implementation("info.picocli:picocli:4.7.6")

    // Entities
    implementation("org.springframework.boot:spring-boot-starter-data-jpa:3.2.0")

    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web:3.2.0")

    // Google OAuth
    implementation("com.google.auth:google-auth-library-oauth2-http:1.11.0")
    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test:3.2.0")
    testImplementation("io.mockk:mockk:1.13.9")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("com.h2database:h2:2.2.224")
}

application {
    mainClass.set("OAuthLoginKt")
}

kotlin {
    jvmToolchain(21)
}

tasks.named<JavaExec>("run") {
    dependsOn(":frontend:wasmJsBrowserDevelopmentExecutableDistribution")
}

sourceSets {
    main {
        kotlin.srcDir("src/main")
    }
    test {
        kotlin.srcDir("src/test")
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<Jar>("cliFatJar") {
    archiveBaseName.set("cli")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "com.cs30.cli.MainKt"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    with(tasks.jar.get())
}
