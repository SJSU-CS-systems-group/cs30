package cli

import com.cs30.cli.GlobalOptions
import com.cs30.cli.parseGlobalOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Same coverage as the old manual-parsing-loop version of this test, just against the current
 * picocli-based GlobalOptions/parseGlobalOptions mechanism (main() reads --token/--db-* off
 * `global` after this call, rather than out of a returned props map).
 */
class MainArgsParsingTest {

    @Test
    fun `token flag after subcommand is extracted and stripped from cliArgs`() {
        val global = GlobalOptions()
        val cliArgs = parseGlobalOptions(
            global,
            arrayOf("addcourse", "--course-file", "course.yml", "--token", "abc123")
        )
        assertEquals("abc123", global.token)
        assertEquals(listOf("addcourse", "--course-file", "course.yml"), cliArgs)
    }

    @Test
    fun `token flag before subcommand is extracted the same way`() {
        val global = GlobalOptions()
        val cliArgs = parseGlobalOptions(
            global,
            arrayOf("--token", "abc123", "addcourse", "--course-file", "course.yml")
        )
        assertEquals("abc123", global.token)
        assertEquals(listOf("addcourse", "--course-file", "course.yml"), cliArgs)
    }

    @Test
    fun `token equals-form is extracted`() {
        val global = GlobalOptions()
        val cliArgs = parseGlobalOptions(
            global,
            arrayOf("addcourse", "--course-file", "course.yml", "--token=abc123")
        )
        assertEquals("abc123", global.token)
        assertEquals(listOf("addcourse", "--course-file", "course.yml"), cliArgs)
    }

    @Test
    fun `no token flag leaves it unset - main() is what falls back to the env var`() {
        val global = GlobalOptions()
        parseGlobalOptions(global, arrayOf("addcourse", "--course-file", "course.yml"))
        assertNull(global.token)
    }

    @Test
    fun `server flag is extracted and stripped wherever it appears`() {
        val global = GlobalOptions()
        val cliArgs = parseGlobalOptions(
            global,
            arrayOf("course2canvas", "--server", "https://sjsu.cs30.app", "--cs30-lab", "1", "--token=abc123")
        )
        assertEquals("https://sjsu.cs30.app", global.server)
        assertEquals("abc123", global.token)
        assertEquals(listOf("course2canvas", "--cs30-lab", "1"), cliArgs)

        val before = GlobalOptions()
        val rest = parseGlobalOptions(before, arrayOf("--server=https://x", "submissions2canvas"))
        assertEquals("https://x", before.server)
        assertEquals(listOf("submissions2canvas"), rest)
    }

    @Test
    fun `db flags and token flag together dont interfere with each other`() {
        val global = GlobalOptions()
        val cliArgs = parseGlobalOptions(
            global,
            arrayOf(
                "addcourse", "--course-file", "course.yml",
                "--db-url", "jdbc:postgresql://localhost:5432/cs30db",
                "--db-user", "cs30",
                "--db-pass", "secret",
                "--token", "abc123",
            )
        )
        assertEquals("jdbc:postgresql://localhost:5432/cs30db", global.dbUrl)
        assertEquals("cs30", global.dbUser)
        assertEquals("secret", global.dbPass)
        assertEquals("abc123", global.token)
        assertEquals(listOf("addcourse", "--course-file", "course.yml"), cliArgs)
    }
}
