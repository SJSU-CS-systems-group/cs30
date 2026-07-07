plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot") version "3.2.0"
    id("io.spring.dependency-management") version "1.1.4"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.ninja-squad:springmockk:4.0.2")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

springBoot {
    mainClass.set("com.cs30.judge.JudgeApplicationKt")
}

// Bundle the single shared config so `java -jar kt-judge.jar` is self-contained.
tasks.processResources {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from("../application.properties")
}
