package auth

import java.io.File

/**
 * The lab kiosk secret for the desktop app, and the header name used to present it.
 *
 * The kiosk launcher exports the secret into the app's environment before starting it, so a process
 * started from any other OS account simply does not have it and cannot read another user's
 * environment. That is what makes the attestation per-account rather than per-installer:
 * packageDeb/packageMsi produce one installer for every student, so a build-time value could never
 * be a secret.
 *
 * The header name, the environment-variable name, and an optional explicit file path all flow from
 * `application.properties` at build time via -D JVM args (see frontend/build.gradle.kts), the same
 * mechanism `cs30.backend.url` and `cs30.maxCustomTestCases` already use. Only the OS-convention
 * fallback paths are literals, and `cs30.kiosk.secret-file` overrides them.
 *
 * Deliberately in desktopMain and not commonMain: commonMain also compiles to wasmJs, where page
 * JavaScript could read this. The web app never learns the secret — it uses an HttpOnly cookie the
 * server sets. The secret is never logged.
 */
object KioskSecretDesktop {

    /** Must match the backend's `cs30.kiosk.header-name`. */
    val headerName: String = System.getProperty(HEADER_NAME_PROPERTY, DEFAULT_HEADER_NAME)

    /** Null when the app was not launched by the kiosk launcher, which is the normal desktop case. */
    val value: String? = readFromEnvironment() ?: readFromFile()

    private fun readFromEnvironment(): String? {
        val envVarName = System.getProperty(ENV_VAR_PROPERTY, DEFAULT_ENV_VAR)
        return System.getenv(envVarName)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun readFromFile(): String? {
        val configuredPath = System.getProperty(SECRET_FILE_PROPERTY).orEmpty()
        val secretFile =
            if (configuredPath.isNotEmpty()) File(configuredPath) else defaultSecretFile()
        return runCatching { secretFile.readText().trim() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * Windows lab vs Linux lab, resolved at runtime rather than baked into the installer, so one
     * build works on both (and on a developer's macOS machine, which falls through to the Unix path).
     */
    private fun defaultSecretFile(): File {
        val programData = System.getenv(WINDOWS_PROGRAM_DATA_VAR)
        return if (programData != null) File(programData, WINDOWS_RELATIVE_PATH) else File(UNIX_PATH)
    }

    private const val HEADER_NAME_PROPERTY = "cs30.kiosk.headerName"
    private const val ENV_VAR_PROPERTY = "cs30.kiosk.envVar"
    private const val SECRET_FILE_PROPERTY = "cs30.kiosk.secretFile"
    private const val DEFAULT_HEADER_NAME = "X-CS30-Kiosk"
    private const val DEFAULT_ENV_VAR = "CS30_KIOSK_SECRET"
    private const val WINDOWS_PROGRAM_DATA_VAR = "PROGRAMDATA"
    private const val WINDOWS_RELATIVE_PATH = "CS30\\kiosk.secret"
    private const val UNIX_PATH = "/etc/cs30/kiosk.secret"
}
