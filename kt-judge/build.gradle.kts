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
}

kotlin {
    jvmToolchain(17)
}

springBoot {
    mainClass.set("com.cs30.judge.JudgeApplicationKt")
}

// Bundle the single shared config so `java -jar kt-judge.jar` is self-contained.
tasks.processResources {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from("../application.properties")
}
