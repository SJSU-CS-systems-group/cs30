package com.cs30.judge

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

// The judge server. Its own executable jar; run with `java -jar kt-judge.jar`.
@SpringBootApplication
@ConfigurationPropertiesScan
class JudgeApplication

fun main(args: Array<String>) {
    runApplication<JudgeApplication>(*args)
}
