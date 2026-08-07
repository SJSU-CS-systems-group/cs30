package cli

import com.cs30.cli.CliApplication
import com.cs30.server.service.CliTokenService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import picocli.CommandLine.IFactory
import java.io.ByteArrayOutputStream
import java.io.PrintStream

@SpringBootTest(classes = [CliApplication::class])
@Transactional
class CliApplicationGatingTest {

    @Autowired
    lateinit var factory: IFactory

    @Autowired
    lateinit var cliTokenService: CliTokenService

    private fun captureStderr(block: () -> Unit): String {
        val original = System.err
        val buffer = ByteArrayOutputStream()
        System.setErr(PrintStream(buffer))
        try {
            block()
        } finally {
            System.setErr(original)
        }
        return buffer.toString()
    }

    @Test
    fun `no token rejects a real command`() {
        val app = CliApplication(factory, cliTokenService, "")
        val stderr = captureStderr { app.run("addcourse", "--course-file", "/nonexistent.yml") }
        assertEquals(1, app.getExitCode())
        assertTrue(stderr.contains("A valid CLI token is required"), "unexpected stderr: $stderr")
    }

    @Test
    fun `garbage token rejects a real command`() {
        val app = CliApplication(factory, cliTokenService, "not-a-real-token")
        val stderr = captureStderr { app.run("addcourse", "--course-file", "/nonexistent.yml") }
        assertEquals(1, app.getExitCode())
        assertTrue(stderr.contains("A valid CLI token is required"), "unexpected stderr: $stderr")
    }

    @Test
    fun `ta token is blocked from addcourse`() {
        val ta = cliTokenService.getOrCreateTaToken("ta-gating-1@test.edu")
        val app = CliApplication(factory, cliTokenService, ta.rawToken!!)
        val stderr = captureStderr { app.run("addcourse", "--course-file", "/nonexistent.yml") }
        assertEquals(1, app.getExitCode())
        assertTrue(stderr.contains("requires an admin token"), "unexpected stderr: $stderr")
    }

    @Test
    fun `ta token is blocked from setta and removeta and changeenddate`() {
        val ta = cliTokenService.getOrCreateTaToken("ta-gating-2@test.edu")
        for (restricted in listOf(
            arrayOf("setta", "--course-code", "x", "--year", "2024", "--semester", "Fall", "--section", "1", "--email", "a@b.com"),
            arrayOf("removeta", "--course-code", "x", "--year", "2024", "--semester", "Fall", "--section", "1"),
            arrayOf("changeenddate", "--course-code", "x", "--year", "2024", "--semester", "Fall", "--section", "1", "--end-date", "2024-12-31"),
        )) {
            val app = CliApplication(factory, cliTokenService, ta.rawToken!!)
            val stderr = captureStderr { app.run(*restricted) }
            assertEquals(1, app.getExitCode(), "expected ${restricted[0]} to be blocked")
            assertTrue(stderr.contains("requires an admin token"), "expected block message for ${restricted[0]}, got: $stderr")
        }
    }

    @Test
    fun `ta token is NOT blocked from findstudent (not on the restricted list)`() {
        val ta = cliTokenService.getOrCreateTaToken("ta-gating-3@test.edu")
        val app = CliApplication(factory, cliTokenService, ta.rawToken!!)
        val stderr = captureStderr { app.run("findstudent", "--email", "student@test.edu") }
        // Whatever happens next (found/not found) is fine - the point is our own gate didn't reject it.
        assertFalse(stderr.contains("requires an admin token"), "should not have been blocked by role gate: $stderr")
    }

    @Test
    fun `admin token is NOT blocked from addcourse`() {
        val admin = cliTokenService.getOrCreateAdminToken("admin-gating-1@test.edu")
        val app = CliApplication(factory, cliTokenService, admin.rawToken!!)
        val stderr = captureStderr { app.run("addcourse", "--course-file", "/nonexistent.yml") }
        // It'll fail because the file doesn't exist, not because of the role gate.
        assertFalse(stderr.contains("requires an admin token"), "admin should not be blocked: $stderr")
        assertTrue(stderr.contains("File not found"), "expected to reach the real command's own file check: $stderr")
    }

    @Test
    fun `--help bypasses the token gate entirely`() {
        val app = CliApplication(factory, cliTokenService, "")
        captureStderr { app.run("--help") }
        assertEquals(0, app.getExitCode())
    }
}
