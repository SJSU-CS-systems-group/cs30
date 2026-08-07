plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
    id("org.springframework.boot") version "3.2.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "edu.sjsu"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework:spring-web")
    implementation("info.picocli:picocli-spring-boot-starter:4.7.6")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    implementation(project(":backend"))

    // Database drivers - add the ones you need
    runtimeOnly("com.h2database:h2")                    // H2 (in-memory/file)
    runtimeOnly("org.postgresql:postgresql")            // PostgreSQL
    runtimeOnly("com.mysql:mysql-connector-j")          // MySQL
    runtimeOnly("org.xerial:sqlite-jdbc:3.45.3.0")      // SQLite
    // SQLite's Hibernate dialect lives outside hibernate-core
    runtimeOnly("org.hibernate.orm:hibernate-community-dialects")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.mockk:mockk:1.13.9")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("com.h2database:h2")
}

kotlin {
    jvmToolchain(21)
}

sourceSets {
    main {
        kotlin.srcDir("src/main")
    }
    test {
        kotlin.srcDir("src/test")
    }
}

tasks.processResources {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from("../application.properties")
    // Bundle the frontend web app for server mode
    from(project(":frontend").tasks.named("wasmJsBrowserDistribution")) {
        into("static")
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveBaseName.set("cs30")
    mainClass.set("com.cs30.cli.MainKt")
}