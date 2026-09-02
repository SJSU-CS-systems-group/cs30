package cli

import com.cs30.cli.AddOverride
import com.cs30.cli.Course2Canvas
import com.cs30.cli.GlobalOptions
import com.cs30.cli.ListOverrides
import com.cs30.cli.REMOTE_COMMANDS
import com.cs30.cli.RemoteSettings
import com.cs30.cli.RemoveOverride
import com.cs30.cli.Submissions2Canvas
import com.cs30.cli.readConfigFiles
import com.cs30.cli.remoteCommand
import com.cs30.cli.remoteSettings
import com.cs30.cli.resolvePlaceholders
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * How the Canvas commands find their settings without the Spring application: the same places,
 * in the same order of precedence, as the commands that still have one.
 */
class RemoteSettingsTest {

    @TempDir
    lateinit var dir: File

    private fun env(vararg pairs: Pair<String, String>): (String) -> String? = { name -> mapOf(*pairs)[name] }

    private val file = mapOf(
        "cs30.backend.url" to "https://file",
        "cs30.cli.token" to "file-token",
        "canvas.url" to "https://canvas-file",
        "canvas.token" to "canvas-file-token",
    )

    private val environment = env(
        "CS30_BACKEND_URL" to "https://env",
        "CS30_ADMIN_TOKEN" to "env-token",
        "CANVAS_URL" to "https://canvas-env",
        "CANVAS_TOKEN" to "canvas-env-token",
    )

    @Test
    fun `the command line wins over the environment, which wins over the file`() {
        val flags = GlobalOptions().apply { server = "https://flag"; token = "flag-token" }
        assertEquals(
            RemoteSettings("https://flag", "flag-token", "https://canvas-env", "canvas-env-token"),
            remoteSettings(flags, environment, file),
        )
        assertEquals(
            RemoteSettings("https://env", "env-token", "https://canvas-env", "canvas-env-token"),
            remoteSettings(GlobalOptions(), environment, file),
        )
        assertEquals(
            RemoteSettings("https://file", "file-token", "https://canvas-file", "canvas-file-token"),
            remoteSettings(GlobalOptions(), { null }, file),
        )
        assertEquals(
            RemoteSettings("", "", "", ""),
            remoteSettings(GlobalOptions(), { null }, emptyMap()),
            "nothing configured is empty strings, which the clients report when first used",
        )
    }

    @Test
    fun `a blank value does not shadow the next source`() {
        val blank = env("CS30_BACKEND_URL" to "", "CS30_ADMIN_TOKEN" to " ")
        val settings = remoteSettings(GlobalOptions().apply { server = "" }, blank, file)
        assertEquals("https://file", settings.serverUrl)
        assertEquals("file-token", settings.token)
    }

    @Test
    fun `placeholders in the file resolve against the environment, as they would under Spring`() {
        val withPlaceholders = mapOf(
            "canvas.token" to "\${CANVAS_TOKEN:}",
            "canvas.url" to "\${CANVAS_URL:https://sjsu.instructure.com}",
            "cs30.backend.url" to "\${MISSING}",
            "cs30.cli.token" to "plain",
        )
        val settings = remoteSettings(GlobalOptions(), env("CANVAS_TOKEN" to "12~abc"), withPlaceholders)
        assertEquals("12~abc", settings.canvasToken)
        assertEquals("https://sjsu.instructure.com", settings.canvasUrl, "the default applies when the variable is unset")
        assertEquals("", settings.serverUrl, "no default and no variable is empty")
        assertEquals("plain", settings.token)
    }

    @Test
    fun `resolvePlaceholders leaves ordinary text alone and handles several in one value`() {
        val lookup = env("A" to "1", "B" to "2")
        assertEquals("plain value", resolvePlaceholders("plain value", lookup))
        assertEquals("1-2", resolvePlaceholders("\${A}-\${B}", lookup))
        assertEquals("x-y", resolvePlaceholders("x-\${C:y}", lookup))
        assertEquals("x-", resolvePlaceholders("x-\${C}", lookup))
        assertEquals("jdbc:h2:mem:db", resolvePlaceholders("\${DB_URL:jdbc:h2:mem:db}", lookup), "a default may itself contain colons")
    }

    @Test
    fun `readConfigFiles merges the files in order, later ones winning, and skips what is not there`() {
        val one = File(dir, "one.properties").apply {
            writeText("cs30.backend.url=https://one\ncs30.cli.token=t1\n")
        }
        val two = File(dir, "two.properties").apply {
            writeText("# only the url\ncs30.backend.url=https://two\n")
        }

        val merged = readConfigFiles("${one.path}, ${two.path}")
        assertEquals("https://two", merged["cs30.backend.url"])
        assertEquals("t1", merged["cs30.cli.token"])

        assertEquals("https://one", readConfigFiles(one.path)["cs30.backend.url"])
        assertFalse(readConfigFiles(File(dir, "missing.properties").path).containsKey("cs30.backend.url"))
        assertFalse(readConfigFiles(null).containsKey("cs30.backend.url"))
    }

    @Test
    fun `only the Canvas and override commands run remotely`() {
        assertEquals(
            setOf("course2canvas", "submissions2canvas", "addoverride", "removeoverride", "listoverrides"),
            REMOTE_COMMANDS,
        )

        val settings = RemoteSettings("", "", "", "")
        assertTrue(remoteCommand("course2canvas", settings) is Course2Canvas)
        assertTrue(remoteCommand("submissions2canvas", settings) is Submissions2Canvas)
        assertTrue(remoteCommand("addoverride", settings) is AddOverride)
        assertTrue(remoteCommand("removeoverride", settings) is RemoveOverride)
        assertTrue(remoteCommand("listoverrides", settings) is ListOverrides)
        // Everything else still needs the database, and so the Spring application.
        assertThrows(IllegalArgumentException::class.java) { remoteCommand("addcourse", settings) }
    }
}
