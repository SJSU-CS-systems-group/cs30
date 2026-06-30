package com.cs30.server.app

import com.cs30.judge.JudgeApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@ComponentScan(basePackages = ["com.cs30.server"])
@EntityScan(basePackages = ["com.cs30.server.models"])
@EnableJpaRepositories(basePackages = ["com.cs30.server.repository"])
@EnableScheduling
class Application

// One fat jar, two roles. With `--spring.profiles.active=judge` the launcher
// boots the judge server (com.cs30.judge); otherwise the backend. Each context
// scans only its own package, so the two never share beans (Shape B).
fun main(args: Array<String>) {
    if (isJudgeProfile(args)) {
        SpringApplicationBuilder(JudgeApplication::class.java).run(*args)
    } else {
        runApplication<Application>(*args)
    }
}

private fun isJudgeProfile(args: Array<String>): Boolean {
    val sources = listOf(
        args.firstOrNull { it.startsWith("--spring.profiles.active=") }?.substringAfter("="),
        System.getProperty("spring.profiles.active"),
        System.getenv("SPRING_PROFILES_ACTIVE"),
    )
    return sources.filterNotNull().any { csv -> csv.split(",").map { it.trim() }.contains("judge") }
}
