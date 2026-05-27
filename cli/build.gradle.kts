plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
    id("org.springframework.boot") version "3.2.0"
    id("io.spring.dependency-management") version "1.1.4"
}

group = "edu.sjsu"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("info.picocli:picocli-spring-boot-starter:4.7.6")
    implementation(project(":backend"))

    // Database drivers - add the ones you need
    runtimeOnly("com.h2database:h2")                    // H2 (in-memory/file)
    runtimeOnly("org.postgresql:postgresql")            // PostgreSQL
    runtimeOnly("com.mysql:mysql-connector-j")          // MySQL
}

kotlin {
    jvmToolchain(21)
}

sourceSets {
    main {
        kotlin.srcDir("src/main")
    }
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveBaseName.set("cs30-cli")
    mainClass.set("com.cs30.cli.MainKt")
}