plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    kotlin("plugin.spring")
    id("org.springframework.boot") version "3.2.0"
    id("io.spring.dependency-management") version "1.1.4"
    application
}

group = "edu.sjsu"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // Shared data models (labx.data.*)
    implementation(project(":data"))

    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // Jackson for Kotlin (JSON serialization in Spring)
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // Kotlinx serialization (for shared data types)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // PostgreSQL JDBC driver
    implementation("org.postgresql:postgresql:42.7.1")

    // CLI
    implementation("info.picocli:picocli:4.7.6")

    // Entities
    implementation("org.springframework.boot:spring-boot-starter-data-jpa:3.2.0")

    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web:3.2.0")

    // Google OAuth
    implementation("com.google.auth:google-auth-library-oauth2-http:1.11.0")
    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.mockk:mockk:1.13.9")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    runtimeOnly("com.h2database:h2:2.2.224")
}

application {
    mainClass.set("com.cs30.server.app.ApplicationKt")
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
        resources.setSrcDirs(listOf(".."))
        resources.include("application.properties", "application.yml", "application.yaml")
    }
    test {
        kotlin.srcDir("src/test")
    }
}

tasks.processResources {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
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
