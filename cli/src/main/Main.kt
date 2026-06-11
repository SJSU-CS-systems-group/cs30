package com.cs30.cli

import java.time.LocalDate
import java.time.LocalDateTime
import com.fasterxml.jackson.annotation.JsonFormat
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.ExitCodeGenerator
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.stereotype.Component
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.IFactory
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option
import kotlin.system.exitProcess

data class LabInput(
    val number: Int,
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    val startDateTime: LocalDateTime,
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    val endDateTime: LocalDateTime
)

data class SectionInput(
    val number: Int,
    val labs: List<LabInput> = emptyList(),
    val students: List<String> = emptyList()
)

data class CourseInput(
    val code: String,
    val year: Int,
    val semester: String,
    @JsonFormat(pattern = "yyyy-MM-dd")
    val startDate: LocalDate,
    @JsonFormat(pattern = "yyyy-MM-dd")
    val endDate: LocalDate,
    val studentGitRepo: String = "",
    val problemGitRepo: String = "",
    val language: String = "",
    val sections: List<SectionInput> = emptyList()
)

@SpringBootApplication(scanBasePackages = ["com.cs30.cli", "com.cs30.server.service"])
@EntityScan("com.cs30.server.models")
@EnableJpaRepositories("com.cs30.server.repository")
class CliApplication(
    private val factory: IFactory
) : CommandLineRunner, ExitCodeGenerator {

    private var exitCode: Int = 0

    override fun run(vararg args: String) {
        // Use class-based CommandLine so picocli creates instances during parsing
        val cmd = CommandLine(MainCommand::class.java, factory)
        exitCode = cmd.execute(*args)
    }

    override fun getExitCode(): Int = exitCode
}

@Command(
    name = "cs30",
    mixinStandardHelpOptions = true,
    version = ["1.0"],
    description = ["CS30 Course Management CLI"],
    subcommands = [
        AddCourse::class,
        AddStudent::class,
        ChangeEndDate::class,
        RemoveCourse::class,
        RemoveStudent::class,
        FindCourse::class,
        FindStudent::class,
        AddProblem::class,
        AddLabs::class,
    ]
)
@Component
class MainCommand {

    @Option(names = ["--db-url"], description = ["Database JDBC URL"])
    var dbUrl: String? = null

    @Option(names = ["--db-user"], description = ["Database username"])
    var dbUser: String? = null

    @Option(names = ["--db-pass"], description = ["Database password"])
    var dbPass: String? = null
}

abstract class BaseCommand {
    @Mixin
    lateinit var cli: CliOptions
}

fun main(args: Array<String>) {
    // Allow passing DB config via command line
    val appArgs = mutableListOf<String>()
    val cliArgs = mutableListOf<String>()

    var i = 0
    while (i < args.size) {
        when {
            args[i] == "--db-url" && i + 1 < args.size -> {
                appArgs.add("--spring.datasource.url=${args[i + 1]}")
                i += 2
            }
            args[i].startsWith("--db-url=") -> {
                appArgs.add("--spring.datasource.url=${args[i].substringAfter("=")}")
                i++
            }
            args[i] == "--db-user" && i + 1 < args.size -> {
                appArgs.add("--spring.datasource.username=${args[i + 1]}")
                i += 2
            }
            args[i].startsWith("--db-user=") -> {
                appArgs.add("--spring.datasource.username=${args[i].substringAfter("=")}")
                i++
            }
            args[i] == "--db-pass" && i + 1 < args.size -> {
                appArgs.add("--spring.datasource.password=${args[i + 1]}")
                i += 2
            }
            args[i].startsWith("--db-pass=") -> {
                appArgs.add("--spring.datasource.password=${args[i].substringAfter("=")}")
                i++
            }
            else -> {
                cliArgs.add(args[i])
                i++
            }
        }
    }

    // Combine Spring args with CLI args
    val allArgs = (appArgs + cliArgs).toTypedArray()
    exitProcess(SpringApplication.exit(SpringApplication.run(CliApplication::class.java, *allArgs)))
}