package com.cs30.cli

import picocli.CommandLine.Command
import picocli.CommandLine.Help.Ansi
import picocli.CommandLine.Option
import java.io.File
import java.sql.DriverManager
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

/**
 * The result of one thing this command looks at. A check that is not [required] is reported but
 * does not decide the exit code, because not every machine running the tool runs the server.
 */
internal data class Check(
    val name: String,
    val ok: Boolean,
    val detail: String,
    val required: Boolean = true
)

/**
 * Asks for the settings the tool needs, checks them, and offers to write them where the tool
 * looks for them.
 *
 * It runs without the application context the other commands share, because the settings it asks
 * about are the ones that context needs to start: a machine that has not been set up yet is
 * exactly the machine this command has to work on.
 */
@Command(
    name = Doctor.NAME,
    mixinStandardHelpOptions = true,
    description = ["Check the setup this tool needs, and walk through what is missing"]
)
class Doctor : Callable<Int> {

    @Option(
        names = ["--check"],
        description = ["Report on the current setup without asking for anything or writing anything"]
    )
    var checkOnly: Boolean = false

    /**
     * The file this works on: whatever --config named, or the one the tool would read anyway.
     * Filled in by main(), which parses the global options before any command runs.
     */
    var configFile: String? = null

    @Option(
        names = ["--reconfigure"],
        description = ["Ask about every setting, not only the ones that are not configured yet"]
    )
    var reconfigure: Boolean = false

    private val prompt = Prompt()

    override fun call(): Int {
        val target = setupFile(configFile)
        val settings = readProperties(target)

        println("Checking the CS30 setup")
        println()
        println("Configuration file: ${target.path}${if (target.isFile) "" else "  (not there yet)"}")
        println()

        if (!checkOnly) askFor(settings)

        val checks = runChecks(settings)

        println()
        checks.forEach { println("  ${mark(it.ok)} ${it.name}: ${it.detail}") }
        println()

        val failed = checks.filter { it.required && !it.ok }
        if (failed.isEmpty()) {
            println("Setup looks good.")
        } else {
            println("Setup is not complete yet: ${failed.joinToString(", ") { it.name }}.")
            if (checkOnly) println("Run 'cs30 ${NAME}' without --check to be walked through it.")
        }

        return if (failed.isEmpty()) 0 else 1
    }

    /** Asks about the settings that are not configured yet, and saves what comes back. */
    private fun askFor(settings: MutableMap<String, String>) {
        val before = LinkedHashMap(settings)
        val questions = questions(settings)
        val configured = questions.filter { settings.containsKey(it.key) }
        val toAsk = if (reconfigure) questions else questions - configured.toSet()

        if (configured.isNotEmpty()) {
            println("Already configured:")
            configured.forEach { println("  ${it.key} = ${it.show(settings.getValue(it.key))}") }
            if (!reconfigure) println("  (--reconfigure asks about these too)")
            println()
        }

        if (toAsk.isEmpty()) {
            println("Nothing left to ask about; checking what is there.")
        } else {
            println("Press enter to keep what is shown in brackets.")
            toAsk.forEach { question ->
                println()
                question.explain?.invoke()
                val current = settings[question.key] ?: question.default
                val answer =
                    if (question.secret) prompt.askSecret(question.text, current)
                    else prompt.ask(question.text, current)
                when {
                    answer.isNotBlank() -> settings[question.key] = answer
                    question.keepWhenEmpty -> settings[question.key] = ""
                    else -> settings.remove(question.key)
                }
            }
        }

        // A blank answer means "not set", except where empty is itself an answer
        val keepEmpty = questions.filter { it.keepWhenEmpty }.map { it.key }.toSet()
        settings.entries.removeIf { it.value.isBlank() && it.key !in keepEmpty }

        // The schema is Hibernate's; without this there is nothing to create the tables. Only
        // worth having once there is a database for it to create them in.
        if (settings["spring.datasource.url"] != null && settings["spring.jpa.hibernate.ddl-auto"] == null) {
            settings["spring.jpa.hibernate.ddl-auto"] = "update"
            println()
            println("Adding spring.jpa.hibernate.ddl-auto=update, which is what creates the tables.")
        }

        val target = setupFile(configFile)
        println()
        if (settings == before) {
            println("Nothing changed, so ${target.path} is left as it is.")
            return
        }

        if (prompt.confirm("Save these settings to ${target.path}?")) {
            writeProperties(target, settings)
            println("Written.")
        } else {
            println("Nothing written - the settings below were only checked.")
        }
    }

    /** What this command asks about, in the order it asks. */
    private fun questions(settings: Map<String, String>) = listOf(
        Question("spring.datasource.url", "Database JDBC URL", DEFAULT_DB_URL, explain = ::explainDatabaseUrl),
        Question("spring.datasource.username", "Database username", keepWhenEmpty = true),
        Question("spring.datasource.password", "Database password", secret = true, keepWhenEmpty = true),
        Question("google.client-id", "Google OAuth client id", explain = { explainOAuth(settings) }),
        Question("google.client-secret", "Google OAuth client secret", secret = true)
    )

    private fun runChecks(settings: Map<String, String>): List<Check> = listOf(
        checkGit(),
        checkDatabase(
            settings["spring.datasource.url"],
            settings["spring.datasource.username"],
            settings["spring.datasource.password"],
            settings["spring.jpa.hibernate.ddl-auto"]
        ),
        checkServerCredentials(settings["google.client-id"], settings["google.client-secret"])
    )

    companion object {
        const val NAME = "doctor"

        private const val DEFAULT_DB_URL = "jdbc:postgresql://localhost:5432/cs30db"
    }
}

/** One thing this command asks about, and what it says before asking. */
internal data class Question(
    val key: String,
    val text: String,
    val default: String = "",
    val secret: Boolean = false,
    /** Whether an empty answer is an answer - a database with no password is set up, not unset. */
    val keepWhenEmpty: Boolean = false,
    val explain: (() -> Unit)? = null
) {
    /** How the configured value is shown back: passwords and secrets are not. */
    fun show(value: String): String = when {
        value.isEmpty() -> "(empty)"
        secret -> "*".repeat(value.length.coerceAtMost(8))
        else -> value
    }
}

/** What a JDBC URL looks like, for the drivers this jar actually carries. */
internal fun databaseUrlExamples(): List<String> {
    val drivers = DriverManager.drivers().map { it.javaClass.name }.toList()
    return KNOWN_DATABASES
        .filter { known -> drivers.any { it.startsWith(known.driverPackage) } }
        .map { "${it.name.padEnd(12)}${it.example}" }
}

private fun explainDatabaseUrl() {
    println("A JDBC URL says which database to talk to and where it is: jdbc:<database>:<location>.")
    println("The drivers in this jar are:")
    databaseUrlExamples().forEach { println("  $it") }
    println("Anything in <angle brackets> is yours to fill in. The database has to exist already;")
    println("its tables do not - those are created on first use.")
}

private fun explainOAuth(settings: Map<String, String>) {
    println("Students sign in to the server with Google, which needs a client id and secret.")
    println("These are only used by 'cs30 serve' - leave them empty on a machine that just runs")
    println("commands. To get a pair:")
    println("  1. Open https://console.cloud.google.com/apis/credentials")
    println("  2. Create credentials -> OAuth client ID -> Web application")
    println("  3. Add ${settings["google.redirect-uri"] ?: DEFAULT_REDIRECT_URI} as an authorized redirect URI")
    println("  4. Copy the client id and the client secret it shows you")
}

/** Whether git is on the PATH, which every command that touches a problem repository needs. */
internal fun checkGit(): Check = try {
    val process = ProcessBuilder("git", "--version").redirectErrorStream(true).start()
    val version = process.inputStream.bufferedReader().readText().trim()
    process.waitFor(10, TimeUnit.SECONDS)
    if (process.exitValue() == 0) Check("git", true, version)
    else Check("git", false, "git --version failed: $version")
} catch (e: Exception) {
    Check("git", false, "not on the PATH (${e.message})")
}

/**
 * Whether the configured database can be reached with the configured credentials, and whether
 * the tables are there. The schema is Hibernate's, created on first use when
 * [schemaManagement] - spring.jpa.hibernate.ddl-auto - says so, so an empty database is only a
 * problem when nothing is set to fill it.
 */
internal fun checkDatabase(url: String?, username: String?, password: String?, schemaManagement: String?): Check {
    if (url.isNullOrBlank()) return Check("database", false, "no JDBC URL configured")

    return try {
        DriverManager.setLoginTimeout(SETUP_LOGIN_TIMEOUT_SECONDS)
        DriverManager.getConnection(url, username.orEmpty(), password.orEmpty()).use { connection ->
            val metaData = connection.metaData
            val database = "${metaData.databaseProductName} ${metaData.databaseProductVersion} at $url"

            val tables = metaData.getTables(null, null, "%", arrayOf("TABLE")).use { rows ->
                generateSequence { if (rows.next()) rows.getString("TABLE_NAME") else null }.toList()
            }

            when {
                tables.any { it.equals(COURSES_TABLE, ignoreCase = true) } -> Check("database", true, database)
                schemaManagement in SCHEMA_CREATING_SETTINGS ->
                    Check("database", true, "$database, with the tables still to be created on first use")
                else -> Check(
                    "database", false,
                    "$database, but it has no $COURSES_TABLE table and " +
                        "spring.jpa.hibernate.ddl-auto is not set to create one"
                )
            }
        }
    } catch (e: Exception) {
        Check("database", false, "cannot connect to $url: ${e.message}")
    }
}

/**
 * How a check reads at a glance. Picocli's AUTO leaves the colour out when the output is not a
 * terminal, so a redirected run stays plain text.
 */
internal fun mark(ok: Boolean): String =
    Ansi.AUTO.string(if (ok) "@|bold,green ✔|@" else "@|bold,red ✘|@")

/** The server needs these two; a machine that only runs commands does not. */
internal fun checkServerCredentials(clientId: String?, clientSecret: String?): Check = when {
    clientId.isNullOrBlank() || clientSecret.isNullOrBlank() ->
        Check(
            "server credentials", false,
            "google.client-id and google.client-secret are needed to run 'cs30 serve' - " +
                "create a pair at https://console.cloud.google.com/apis/credentials",
            required = false
        )
    else -> Check("server credentials", true, "configured", required = false)
}

/** The file this command reads and writes: [explicit] if given, the configured one, or the standard one. */
internal fun setupFile(explicit: String?): File = when {
    explicit != null -> File(explicit)
    else -> defaultConfigFile()?.let { File(it) }
        ?: File(
            configDirectories(System.getProperty("os.name"), System.getProperty("user.home"), System::getenv).first(),
            CONFIG_FILE_NAME
        )
}

/** The settings already in [file], in the order they are written there. */
internal fun readProperties(file: File): MutableMap<String, String> {
    val settings = LinkedHashMap<String, String>()
    if (!file.isFile) return settings

    file.forEachLine { line ->
        val text = line.trim()
        if (text.isNotEmpty() && !text.startsWith("#") && !text.startsWith("!")) {
            val separator = text.indexOfFirst { it == '=' || it == ':' }
            if (separator > 0) {
                settings[text.substring(0, separator).trim()] = text.substring(separator + 1).trim()
            }
        }
    }
    return settings
}

/** Writes [settings] to [file], creating the directory it lives in. Comments are not kept. */
internal fun writeProperties(file: File, settings: Map<String, String>) {
    file.parentFile?.mkdirs()
    val lines = settings.map { (key, value) -> "$key=${value.replace("\\", "\\\\")}" }
    file.writeText((listOf("# CS30 configuration") + lines).joinToString("\n", postfix = "\n"))
}

private const val SETUP_LOGIN_TIMEOUT_SECONDS = 5
private const val DEFAULT_REDIRECT_URI = "http://localhost:8080/callback"

private data class KnownDatabase(val name: String, val driverPackage: String, val example: String)

private val KNOWN_DATABASES = listOf(
    KnownDatabase("PostgreSQL", "org.postgresql", "jdbc:postgresql://<host>:5432/<database>"),
    KnownDatabase("MySQL", "com.mysql", "jdbc:mysql://<host>:3306/<database>"),
    KnownDatabase("H2", "org.h2", "jdbc:h2:file:/path/to/cs30"),
    KnownDatabase("SQLite", "org.sqlite", "jdbc:sqlite:/path/to/cs30.db")
)
private const val COURSES_TABLE = "courses"
private val SCHEMA_CREATING_SETTINGS = setOf("update", "create", "create-drop")

/** Questions asked on the terminal, with the password ones kept off the screen where possible. */
private class Prompt {

    private val console = System.console()

    fun ask(question: String, default: String): String {
        print(if (default.isEmpty()) "$question: " else "$question [$default]: ")
        System.out.flush()
        val answer = readlnOrNull()?.trim()
        return if (answer.isNullOrEmpty()) default else answer
    }

    fun askSecret(question: String, default: String): String {
        if (console == null) return ask(question, default)

        val shown = if (default.isEmpty()) "" else " [unchanged]"
        val answer = console.readPassword("$question$shown: ")?.concatToString()?.trim()
        return if (answer.isNullOrEmpty()) default else answer
    }

    fun confirm(question: String): Boolean {
        print("$question [Y/n]: ")
        System.out.flush()
        // Enter means yes, but no input at all - a closed or redirected stdin - is not an answer,
        // and certainly not permission to write over a file
        val answer = readlnOrNull()?.trim()?.lowercase() ?: return false
        return answer.isEmpty() || answer == "y" || answer == "yes"
    }
}
