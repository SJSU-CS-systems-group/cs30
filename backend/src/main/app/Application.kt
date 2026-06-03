package com.cs30.server.app

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
    runApplication<Application>(*args)
}
