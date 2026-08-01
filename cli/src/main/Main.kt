package com.cs30.cli

import java.time.LocalDate
import java.time.LocalDateTime
import com.fasterxml.jackson.annotation.JsonFormat
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.ExitCodeGenerator
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.MapPropertySource
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.stereotype.Component
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.IFactory
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option
import java.io.File
import kotlin.system.exitProcess

data class ProblemInput(
    val name: String,
    var language: String? = null,
    var note: String? = null
)

data class LabInput(
    val number: Int,
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    val startDateTime: LocalDateTime,
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    val endDateTime: LocalDateTime,
    val problems: List<ProblemInput> = emptyList()
)

data class SectionInput(
    val number: Int,
    val ta: String? = null,
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

data class LabFileInput(
    val code: String,
    val year: Int,
    val semester: String,
    val section: Int,
    val labNumber: Int,
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    val startDateTime: LocalDateTime,
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    val endDateTime: LocalDateTime,
    val problems: List<ProblemInput> = emptyList()
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
    description = ["CS30 Course Management CLI. Use 'serve' to start the web server."],
    subcommands = [
        AddCourse::class,
        AddLab::class,
        AddStudent::class,
        ChangeEndDate::class,
        RemoveCourse::class,
        RemoveStudent::class,
        FindCourse::class,
        FindStudent::class,
        SetTA::class,
        RemoveTA::class,
        AddProblem::class,
        AddProblems::class,
        RemoveProblem::class,
        UpdateProblemLanguage::class,
        CancelLab::class,
        ValidateCourse::class,
    ]
)
@Component
class MainCommand {

    // Consumed by main() before the Spring context starts; declared here so they show up in
    // the usage message and are accepted by the command tree.
    @Mixin
    var global: GlobalOptions = GlobalOptions()
}

/**
 * Options that have to be known before the Spring context is created, because they feed the
 * application configuration itself.
 */
class GlobalOptions {

    @Option(
        names = ["--config"],
        paramLabel = "<path>",
        description = [
            "Configuration file(s) to add to the application configuration, comma-separated.",
            "Defaults to cs30.properties in the standard configuration directories."
        ]
    )
    var config: String? = null

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
    // Check if running in server mode
    if (args.firstOrNull() == "serve") {
        runServer(args.drop(1).toTypedArray())
        return
    }

    // CLI mode - pick up the global options with picocli before the application starts, so the
    // configuration they describe is in place by the time the Spring context is created
    val global = GlobalOptions()
    val cliArgs = parseGlobalOptions(global, args)

    val app = SpringApplication(CliApplication::class.java)

    // Read while the environment is being prepared, so the configuration files are picked up
    // before the context is created
    val defaults = mutableMapOf<String, Any>("spring.main.web-application-type" to "none")
    (global.config ?: defaultConfigFile())?.let { defaults["spring.config.additional-location"] = it }
    app.setDefaultProperties(defaults)

    val dbProps = mutableMapOf<String, Any>()
    global.dbUrl?.let { dbProps["spring.datasource.url"] = it }
    global.dbUser?.let { dbProps["spring.datasource.username"] = it }
    global.dbPass?.let { dbProps["spring.datasource.password"] = it }
    if (dbProps.isNotEmpty()) {
        // In front of every other property source, so options given on the command line win
        // over the configuration files they may also be set in
        app.addInitializers(ApplicationContextInitializer<ConfigurableApplicationContext> { ctx ->
            ctx.environment.propertySources.addFirst(MapPropertySource(DB_OPTIONS_SOURCE, dbProps))
        })
    }

    exitProcess(SpringApplication.exit(app.run(*cliArgs.toTypedArray())))
}

private const val DB_OPTIONS_SOURCE = "cs30CommandLineDatabaseOptions"

private const val CONFIG_FILE_NAME = "cs30.properties"

/**
 * The configuration file to use when --config is not given: the first [CONFIG_FILE_NAME] found
 * in the standard configuration directories, the user's before the machine's. Null when there
 * is none, leaving the application on the configuration built into the jar.
 */
private fun defaultConfigFile(): String? =
    configDirectories(System.getProperty("os.name"), System.getProperty("user.home"), System::getenv)
        .map { File(it, CONFIG_FILE_NAME) }
        .firstOrNull { it.isFile }
        ?.path

/**
 * Where [CONFIG_FILE_NAME] is looked for on [osName], in the order it is looked for. Anything
 * that is not Windows or macOS - including an operating system we don't recognize - is treated
 * as Linux.
 */
internal fun configDirectories(osName: String?, userHome: String?, env: (String) -> String?): List<File> {
    val home = File(userHome ?: ".")
    return when {
        osName.orEmpty().lowercase().startsWith("windows") -> listOfNotNull(
            env("APPDATA")?.let { File(it) },
            env("ProgramData")?.let { File(it) },
        )
        osName.orEmpty().lowercase().startsWith("mac") -> listOf(
            File(home, "Library/Application Support"),
            File("/Library/Application Support"),
        )
        else -> listOf(
            env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let { File(it) }
                ?: File(home, ".config"),
            File("/etc"),
        )
    }
}

/**
 * Fills [global] from [args] and returns the remaining arguments, which belong to the
 * subcommands. Anything picocli does not recognize here is left untouched, so the full command
 * tree can parse it - and report any errors in it - once the application is running.
 */
private fun parseGlobalOptions(global: GlobalOptions, args: Array<String>): List<String> {
    val cmd = CommandLine(global).setUnmatchedArgumentsAllowed(true)
    return try {
        cmd.parseArgs(*args).unmatched()
    } catch (e: CommandLine.ParameterException) {
        args.toList()
    }
}

/**
 * Starts the web server (backend mode).
 * Usage: serve [--config=<path>] [other spring args...]
 */
private fun runServer(args: Array<String>) {
    val springArgs = mutableListOf<String>()

    var i = 0
    while (i < args.size) {
        when {
            args[i].startsWith("--config=") -> {
                springArgs.add("--spring.config.location=${args[i].substringAfter("=")}")
                i++
            }
            args[i] == "--config" && i + 1 < args.size -> {
                springArgs.add("--spring.config.location=${args[i + 1]}")
                i += 2
            }
            else -> {
                springArgs.add(args[i])
                i++
            }
        }
    }

    println("Starting CS30 server...")
    SpringApplication.run(com.cs30.server.app.Application::class.java, *springArgs.toTypedArray())
}