package com.cs30.server.app

import io.github.cdimascio.dotenv.Dotenv
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@SpringBootApplication
@ComponentScan(basePackages = ["com.cs30.server"])
@EntityScan(basePackages = ["com.cs30.server.models"])
@EnableJpaRepositories(basePackages = ["com.cs30.server.repository"])
class Application

fun main(args: Array<String>) {
    // Load .env file BEFORE Spring starts so environment variables are available for property resolution
    val dotenv = Dotenv.configure()
        .ignoreIfMissing()
        .load()
    dotenv.entries().forEach { entry ->
        System.setProperty(entry.key, entry.value)
    }

    runApplication<Application>(*args)
}
