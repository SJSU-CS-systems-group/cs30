package com.cs30.cli

import java.time.LocalDate
import java.time.LocalDateTime
import com.fasterxml.jackson.annotation.JsonFormat
import com.cs30.server.models.CliTokenRole
import com.cs30.server.service.CliTokenService
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.Banner
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
import picocli.CommandLine.Unmatched
import java.io.File
import java.util.concurrent.Callable
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
    private val factory: IFactory,
    private val cliTokenService: CliTokenService,
    @Value("\${cs30.cli.token:}") private val token: String,
) : CommandLineRunner, ExitCodeGenerator {

    private var exitCode: Int = 0

    override fun run(vararg args: String) {
        // --help/--version/no-args aren't real commands - let picocli handle those without a token.
        val needsAuth = args.isNotEmpty() && args.none { it in NO_AUTH_ARGS }
        if (needsAuth) {
            val resolved = cliTokenService.resolveToken(token)
            if (resolved == null) {
                System.err.println("ERROR: A valid CLI token is required. Pass --token or set CS30_ADMIN_TOKEN.")
                exitCode = 1
                return
            }
            // Admins can run anything; every other role (TA today) is blocked from roster/course
            // administration commands - the ones that add/remove courses, students, or TAs.
            val commandName = args[0]
            if (resolved.role != CliTokenRole.ADMIN && commandName in ADMIN_ONLY_COMMANDS) {
                System.err.println("ERROR: '$commandName' requires an admin token.")
                exitCode = 1
                return
            }
        }

        // Use class-based CommandLine so picocli creates instances during parsing
        val cmd = CommandLine(MainCommand::class.java, factory)
        exitCode = cmd.execute(*args)
    }

    override fun getExitCode(): Int = exitCode

    companion object {
        private val NO_AUTH_ARGS = setOf("-h", "--help", "-V", "--version")
        private val ADMIN_ONLY_COMMANDS = setOf(
            "addcourse", "addstudent", "removecourse", "removestudent", "changeenddate", "setta", "removeta"
        )
    }
}

@Command(
    name = "cs30",
    mixinStandardHelpOptions = true,
    version = ["1.0"],
    description = ["CS30 Course Management CLI."],
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
        Serve::class,
        Doctor::class,
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

    @Option(names = ["--token"], description = ["Admin CLI token (overrides CS30_ADMIN_TOKEN and any configured value)"])
    var token: String? = null
}

abstract class BaseCommand {
    @Mixin
    lateinit var cli: CliOptions
}

fun main(args: Array<String>) {
    // Pick up the global options with picocli before anything starts, so that the configuration
    // they describe is in place by the time it is needed - by the Spring context below, or by
    // the commands that run without one
    val global = GlobalOptions()
    val cliArgs = parseGlobalOptions(global, args)

    // These two run without the application the other commands share: the server runs one of its
    // own, and setup has to work on a machine that cannot start one yet. Both take the
    // configuration file --config names, each in the way it needs it.
    when (cliArgs.firstOrNull()) {
        Serve.NAME -> {
            // On success we return rather than exit, leaving the server running
            val serve = Serve().apply { config = global.config ?: defaultConfigFile() }
            val exitCode = standalone(serve, Serve.NAME, cliArgs)
            if (exitCode != CommandLine.ExitCode.OK) exitProcess(exitCode)
            return
        }
        Doctor.NAME -> exitProcess(
            standalone(Doctor().apply { configFile = global.config }, Doctor.NAME, cliArgs)
        )
    }

    val app = SpringApplication(CliApplication::class.java)

    // The banner belongs to the server, not to a command run from a terminal
    app.setBannerMode(Banner.Mode.OFF)

    // Read while the environment is being prepared, so the configuration files are picked up
    // before the context is created
    val defaults = mutableMapOf<String, Any>("spring.main.web-application-type" to "none")
    val configFile = global.config ?: defaultConfigFile()
    configFile?.let { defaults["spring.config.additional-location"] = it }
    // Asking for help - or for nothing at all, which answers with help - is asking for one
    // thing; keep the startup out of its way
    val answeringWithHelp = isHelpRequested(args) || cliArgs.isEmpty()
    if (answeringWithHelp) defaults["logging.level.root"] = "warn"
    app.setDefaultProperties(defaults)

    if (!answeringWithHelp) reportConfiguration(configFile)

    val cliOverrides = mutableMapOf<String, Any>()
    global.dbUrl?.let { cliOverrides["spring.datasource.url"] = it }
    global.dbUser?.let { cliOverrides["spring.datasource.username"] = it }
    global.dbPass?.let { cliOverrides["spring.datasource.password"] = it }
    // Fall back to the env var so CI/automation doesn't have to put the token on the command line.
    (global.token ?: System.getenv("CS30_ADMIN_TOKEN"))?.let { cliOverrides["cs30.cli.token"] = it }
    if (cliOverrides.isNotEmpty()) {
        // In front of every other property source, so options given on the command line win
        // over the configuration files they may also be set in
        app.addInitializers(ApplicationContextInitializer<ConfigurableApplicationContext> { ctx ->
            ctx.environment.propertySources.addFirst(MapPropertySource(CLI_OVERRIDES_SOURCE, cliOverrides))
        })
    }

    exitProcess(SpringApplication.exit(app.run(*cliArgs.toTypedArray())))
}

/**
 * Runs [command] on its own, named as the subcommand [name] it is reached by. [args] still has
 * that name in front of the arguments meant for it.
 */
private fun standalone(command: Any, name: String, args: List<String>): Int =
    CommandLine(command)
        .setCommandName("cs30 $name")
        .execute(*args.drop(1).toTypedArray())

private const val CLI_OVERRIDES_SOURCE = "cs30CommandLineOverrides"

/**
 * Says which settings the run is about to use, on the error stream so that it stays out of what
 * a command prints.
 */
private fun reportConfiguration(configFile: String?) {
    if (configFile != null) {
        System.err.println("Configuration: $configFile")
        return
    }

    val looked = configDirectories(System.getProperty("os.name"), System.getProperty("user.home"), System::getenv)
        .joinToString(", ") { File(it, CONFIG_FILE_NAME).path }
    System.err.println(
        "Configuration: no $CONFIG_FILE_NAME found (looked in $looked) - " +
            "run 'cs30 ${Doctor.NAME}' to write one"
    )
}

private val HELP_FLAGS = setOf("-h", "--help", "-V", "--version")

/** Whether [args] ask for usage or version information rather than for a command to be run. */
private fun isHelpRequested(args: Array<String>): Boolean = args.any { it in HELP_FLAGS }

internal const val CONFIG_FILE_NAME = "cs30.properties"

/**
 * The configuration file to use when --config is not given: the first [CONFIG_FILE_NAME] found
 * in the standard configuration directories, the user's before the machine's. Null when there
 * is none, leaving the application on the configuration built into the jar.
 */
internal fun defaultConfigFile(): String? =
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
            env("APPDATA")?.takeIf { it.isNotBlank() }?.let { File(it) },
            env("ProgramData")?.takeIf { it.isNotBlank() }?.let { File(it) },
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
 * internal (rather than private) so MainArgsParsingTest can exercise it directly, without going
 * through main() itself (which would exitProcess() and kill the test JVM).
 */
internal fun parseGlobalOptions(global: GlobalOptions, args: Array<String>): List<String> {
    val cmd = CommandLine(global).setUnmatchedArgumentsAllowed(true)
    return try {
        cmd.parseArgs(*args).unmatched()
    } catch (e: CommandLine.ParameterException) {
        args.toList()
    }
}

/**
 * Starts the web server. The server is its own Spring Boot application, so this command runs it
 * rather than anything in the context the other commands share.
 */
@Command(
    name = Serve.NAME,
    mixinStandardHelpOptions = true,
    description = ["Start the web server"]
)
class Serve : Callable<Int> {

    @Option(
        names = ["--config"],
        paramLabel = "<path>",
        description = ["Configuration file(s) to run the server with, comma-separated"]
    )
    var config: String? = null

    /** Anything else on the command line, passed to the server as it stands. */
    @Unmatched
    var serverArgs: MutableList<String> = mutableListOf()

    override fun call(): Int {
        val springArgs = mutableListOf<String>()
        // Added to the configuration rather than replacing it, so that the file only has to say
        // what differs from what the jar was built with - the same thing --config means elsewhere
        config?.let { springArgs.add("--spring.config.additional-location=$it") }
        springArgs.addAll(serverArgs)

        println("Starting CS30 server...")
        SpringApplication.run(com.cs30.server.app.Application::class.java, *springArgs.toTypedArray())
        return 0
    }

    companion object {
        const val NAME = "serve"
    }
}
