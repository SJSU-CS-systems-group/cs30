package com.cs30.cli

import java.io.File
import java.util.concurrent.Callable

/**
 * The commands that reach cs30 through the server instead of the database, and so run without
 * the Spring application the other commands share. main() dispatches these before it starts one.
 */
internal val REMOTE_COMMANDS: Set<String> = setOf(Course2Canvas.NAME, Submissions2Canvas.NAME)

/** What the remote commands need, resolved from every place it can be configured. */
internal data class RemoteSettings(
    val serverUrl: String,
    val token: String,
    val canvasUrl: String,
    val canvasToken: String,
)

/**
 * Resolves the settings, most specific source last: [properties] (the jar's own
 * application.properties under the configuration file, see [readConfigFiles]), then the
 * environment, then the command line. That is the order Spring gives the other commands, so a
 * value works in the same place for both. `${NAME:default}` placeholders in a property resolve
 * against the environment, again as they would under Spring.
 */
internal fun remoteSettings(
    global: GlobalOptions,
    env: (String) -> String?,
    properties: Map<String, String>,
): RemoteSettings {
    fun setting(option: String?, envName: String, key: String): String =
        option?.takeIf { it.isNotBlank() }
            ?: env(envName)?.takeIf { it.isNotBlank() }
            ?: properties[key]?.let { resolvePlaceholders(it, env) }.orEmpty()

    return RemoteSettings(
        serverUrl = setting(global.server, "CS30_BACKEND_URL", "cs30.backend.url"),
        token = setting(global.token, "CS30_ADMIN_TOKEN", "cs30.cli.token"),
        canvasUrl = setting(null, "CANVAS_URL", "canvas.url"),
        canvasToken = setting(null, "CANVAS_TOKEN", "canvas.token"),
    )
}

private val PLACEHOLDER = Regex("""\$\{([^}:]+)(?::([^}]*))?}""")

/** Replaces each `${NAME}` or `${NAME:default}` in [value] with the environment's NAME, or the default (empty if none). */
internal fun resolvePlaceholders(value: String, env: (String) -> String?): String =
    PLACEHOLDER.replace(value) { match -> env(match.groupValues[1]) ?: match.groupValues[2] }

/**
 * The configuration the remote commands read: the application.properties built into the jar,
 * then each file [config] names (comma-separated, as --config is for the server), later ones
 * winning. Null or a file that is not there just contributes nothing.
 */
internal fun readConfigFiles(config: String?): Map<String, String> {
    val settings = LinkedHashMap<String, String>()
    bundledProperties()?.let { settings.putAll(parseProperties(it)) }
    config?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.forEach { settings.putAll(readProperties(File(it))) }
    return settings
}

/** The application.properties the jar was built with, or null when it carries none. */
private fun bundledProperties(): String? =
    RemoteSettings::class.java.classLoader.getResource(BUNDLED_PROPERTIES)?.readText()

private const val BUNDLED_PROPERTIES = "application.properties"

/**
 * The remote command [name] names, built with the clients it talks through. The clients check
 * their configuration when first used, not here, so --help works with nothing configured.
 */
internal fun remoteCommand(name: String, settings: RemoteSettings): Callable<Int> {
    val cs30 = Cs30ApiClient(settings.serverUrl, settings.token)
    val canvas = CanvasClient(settings.canvasUrl, settings.canvasToken)
    return when (name) {
        Course2Canvas.NAME -> Course2Canvas(cs30, canvas)
        Submissions2Canvas.NAME -> Submissions2Canvas(cs30, canvas)
        else -> throw IllegalArgumentException("'$name' does not run remotely")
    }
}
