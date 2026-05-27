package com.cs30.cli

import com.cs30.server.repository.CourseRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.ExitCodeGenerator
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.IFactory
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.util.concurrent.Callable
import kotlin.system.exitProcess

@SpringBootApplication
@EntityScan("com.cs30.server.models")
@EnableJpaRepositories("com.cs30.server.repository")
class CliApplication(
    private val factory: IFactory,
    private val mainCommand: MainCommand
) : CommandLineRunner, ExitCodeGenerator {

    private var exitCode: Int = 0

    override fun run(vararg args: String) {
        exitCode = CommandLine(mainCommand, factory).execute(*args)
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
        DeleteCourse::class,
        AddStudent::class,
        DeleteStudent::class
    ]
)
@org.springframework.stereotype.Component
class MainCommand : Callable<Int> {

    @Option(names = ["--db-url"], description = ["Database JDBC URL"])
    var dbUrl: String? = null

    @Option(names = ["--db-user"], description = ["Database username"])
    var dbUser: String? = null

    @Option(names = ["--db-pass"], description = ["Database password"])
    var dbPass: String? = null

    override fun call(): Int {
        CommandLine(this).usage(System.out)
        return 0
    }
}

@Command(name = "addcourse", description = ["Add a new course"])
@org.springframework.stereotype.Component
class AddCourse : Callable<Int> {

    @Autowired
    lateinit var courseRepository: CourseRepository

    @Parameters(index = "0", description = ["Course name"])
    lateinit var courseName: String

    @Parameters(index = "1", description = ["Section number"])
    var section: Int = 1

    override fun call(): Int {
        println("Adding course: $courseName (section $section)")
        // courseRepository.save(Course(...))
        return 0
    }
}

@Command(name = "deletecourse", description = ["Delete a course"])
@org.springframework.stereotype.Component
class DeleteCourse : Callable<Int> {

    @Parameters(index = "0", description = ["Course name"])
    lateinit var courseName: String

    override fun call(): Int {
        println("Deleting course: $courseName")
        return 0
    }
}

@Command(name = "addstudent", description = ["Add a new student"])
@org.springframework.stereotype.Component
class AddStudent : Callable<Int> {

    @Parameters(index = "0", description = ["Student email"])
    lateinit var email: String

    @Parameters(index = "1", description = ["First name"])
    lateinit var firstName: String

    @Parameters(index = "2", description = ["Last name"])
    lateinit var lastName: String

    override fun call(): Int {
        println("Adding student: $firstName $lastName ($email)")
        return 0
    }
}

@Command(name = "deletestudent", description = ["Delete a student"])
@org.springframework.stereotype.Component
class DeleteStudent : Callable<Int> {

    @Parameters(index = "0", description = ["Student email"])
    lateinit var email: String

    override fun call(): Int {
        println("Deleting student: $email")
        return 0
    }
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