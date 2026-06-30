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

    // Kotlin judge — same jar runs as the judge under `--spring.profiles.active=judge`.
    implementation(project(":kt-judge"))

    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // IpAddressMatcher for CIDR-based IP whitelisting (no Spring Security auto-config triggered)
    implementation("org.springframework.security:spring-security-web")

    // Jackson for Kotlin (JSON serialization in Spring)
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // PostgreSQL JDBC driver
    implementation("org.postgresql:postgresql:42.7.1")

    // CLI
    implementation("info.picocli:picocli:4.7.6")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.mockk:mockk:1.13.9")
    runtimeOnly("com.h2database:h2:2.2.224")
}

application {
    mainClass.set("com.cs30.server.app.ApplicationKt")
}

kotlin {
    jvmToolchain(21)
}

sourceSets {
    main {
        kotlin.setSrcDirs(listOf("src/main"))
    }
    test {
        kotlin.setSrcDirs(listOf("src/test"))
    }
}

tasks.bootRun {
    systemProperty("spring.profiles.active", "local")
}

tasks.processResources {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from("../application.properties")
    // Bundle the production wasmJs web app into the jar at classpath:/static,
    // so `java -jar` serves the frontend with no external files. bootJar and
    // bootRun both consume processResources, so this is the single wiring point.
    from(project(":frontend").tasks.named("wasmJsBrowserDistribution")) {
        into("static")
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
