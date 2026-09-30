package cli

import com.cs30.cli.CliApplication
import com.cs30.server.dto.CourseInput
import com.cs30.server.service.CliTokenService
import com.cs30.server.repository.CourseRepository
import com.cs30.server.service.CourseAccessService
import com.cs30.server.service.CourseService
import com.cs30.server.service.CourseYamlService
import com.cs30.server.service.GitService
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import picocli.CommandLine.IFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/**
 * The end-to-end property from issue #252: after any change, re-importing the course file the
 * system wrote is a no-op instead of deleting the students added since.
 *
 * Deliberately a full Spring context rather than a unit test - the sync hangs off a real
 * transaction's after-completion phase, and that is the part unit tests cannot exercise. The tests
 * are NOT @Transactional: they need commits to actually happen.
 */
@SpringBootTest(classes = [CliApplication::class])
class CourseYamlSyncIntegrationTest {

    @Autowired lateinit var factory: IFactory
    @Autowired lateinit var cliTokenService: CliTokenService
    @Autowired lateinit var courseService: CourseService
    @Autowired lateinit var courseYamlService: CourseYamlService
    @Autowired lateinit var gitService: GitService
    @Autowired lateinit var courseRepository: CourseRepository
    @Autowired lateinit var courseAccess: CourseAccessService

    @TempDir lateinit var tempDir: File

    private lateinit var studentRepo: String
    private lateinit var code: String
    private lateinit var adminToken: String

    @BeforeEach
    fun setUp() {
        studentRepo = File(tempDir, "students").absolutePath
        gitService.initGitRepo(studentRepo)
        // A unique code per test: the H2 database is shared across the class.
        code = "CMPE${System.nanoTime() % 100_000}"
        // reset, not getOrCreate: only one admin token exists at a time, and getOrCreate hands back
        // the raw value only when it is the one creating it.
        adminToken = cliTokenService.resetAdminToken("admin-$code@test.edu").rawToken!!
    }

    private fun courseFile(vararg sections: Int): File {
        val yaml = buildString {
            appendLine("code: $code")
            appendLine("year: 2026")
            appendLine("semester: Fall")
            appendLine("startDate: \"2026-08-19\"")
            appendLine("endDate: \"2026-12-15\"")
            appendLine("studentGitRepo: $studentRepo")
            appendLine("problemGitRepo: ${File(tempDir, "problems").absolutePath}")
            appendLine("language: Java")
            appendLine("sections:")
            sections.forEach { section ->
                appendLine("  - number: $section")
                appendLine("    tas:")
                appendLine("      - ta$section@sjsu.edu")
                appendLine("      - ta${section}b@sjsu.edu")
                appendLine("    students:")
                appendLine("      - roster$section@sjsu.edu")
                appendLine("    labs:")
                appendLine("      - number: 1")
                appendLine("        startDateTime: \"2026-08-27T16:43:00\"")
                appendLine("        endDateTime: \"2026-08-27T18:00:00\"")
                appendLine("        problems:")
                appendLine("          - name: quoted")
                appendLine("            language: Java")
            }
        }
        return File(tempDir, "course-$code.yml").apply { writeText(yaml) }
    }

    private fun runCli(vararg args: String): Int {
        val originalOut = System.out
        val originalErr = System.err
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        System.setErr(PrintStream(buffer))
        val app = CliApplication(factory, cliTokenService, adminToken)
        try {
            app.run(*args)
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
        return app.getExitCode()
    }

    private fun syncedFile() = File(studentRepo, "course.yml")

    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", studentRepo) + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output.trim()
    }

    private fun commitCount() = git("rev-list", "--count", "HEAD").toIntOrNull() ?: 0

    private fun roster(section: Int): List<String> =
        courseYamlService.findSections(code, 2026, "Fall").single { it.section == section }.students.sorted()

    @Test
    fun `importing a course writes the file once, not once per section`() {
        assertEquals(0, runCli("addcourse", "--course-file", courseFile(1, 2).absolutePath))

        assertTrue(syncedFile().exists(), "course.yml should have been written to the student repo")
        assertEquals(1, commitCount(), "a two-section import should produce one commit, not two")

        val written: CourseInput = courseYamlService.mapper.readValue(syncedFile())
        assertEquals(listOf(1, 2), written.sections.map { it.number })
    }

    @Test
    fun `adding a student updates the file, and re-importing it keeps that student`() {
        runCli("addcourse", "--course-file", courseFile(1).absolutePath)
        val afterImport = commitCount()

        // The exact path that lost three students in issue #252.
        assertEquals(
            0,
            runCli("addstudent", "--course-code", code, "--year", "2026", "--semester", "Fall", "--section", "1", "--email", "added@sjsu.edu")
        )

        val written: CourseInput = courseYamlService.mapper.readValue(syncedFile())
        assertEquals(listOf("added@sjsu.edu", "roster1@sjsu.edu"), written.sections.single().students)
        assertEquals(afterImport + 1, commitCount())

        // Re-import the file the system wrote. Before this change, this deleted the manual add.
        val regenerated = File(tempDir, "regenerated.yml").apply { writeText(syncedFile().readText()) }
        assertEquals(0, runCli("addcourse", "--course-file", regenerated.absolutePath))

        assertEquals(listOf("added@sjsu.edu", "roster1@sjsu.edu"), roster(1))
    }

    @Test
    fun `re-importing the generated file changes nothing, so it makes no new commit`() {
        runCli("addcourse", "--course-file", courseFile(1, 2).absolutePath)
        val before = commitCount()

        val regenerated = File(tempDir, "regenerated.yml").apply { writeText(syncedFile().readText()) }
        assertEquals(0, runCli("addcourse", "--course-file", regenerated.absolutePath))

        assertEquals(before, commitCount(), "an unchanged re-import should not commit anything")
        assertEquals(git("status", "--porcelain"), "", "the working tree should be clean")
    }

    @Test
    fun `changing the end date for all sections writes the file once`() {
        runCli("addcourse", "--course-file", courseFile(1, 2).absolutePath)
        val before = commitCount()

        assertEquals(
            0,
            runCli("changeenddate", "--course-code", code, "--year", "2026", "--semester", "Fall", "--section", "all", "--end-date", "2026-12-20")
        )

        assertEquals(before + 1, commitCount(), "both sections changed in one transaction, so one commit")
        val written: CourseInput = courseYamlService.mapper.readValue(syncedFile())
        assertEquals("2026-12-20", written.endDate.toString())
    }

    @Test
    fun `the written file round-trips through exportcourse unchanged`() {
        runCli("addcourse", "--course-file", courseFile(1, 2).absolutePath)

        val exported = File(tempDir, "exported.yml")
        assertEquals(
            0,
            runCli("exportcourse", "--course-code", code, "--year", "2026", "--semester", "Fall", "--output", exported.absolutePath)
        )

        assertEquals(syncedFile().readText(), exported.readText())
    }

    @Test
    fun `isTa works on a course read outside a transaction`() {
        // taEmails is an @ElementCollection and open-in-view is off, so a Course handed to
        // CourseAccessService by ProblemService/CodeService/LabController is detached by then.
        // If the collection is lazy this throws LazyInitializationException, and every student
        // lab-access check fails with it.
        runCli("addcourse", "--course-file", courseFile(1).absolutePath)
        val courseId = courseYamlService.findSections(code, 2026, "Fall").single().id

        val detached = courseRepository.findById(courseId).orElseThrow()

        assertTrue(courseAccess.isTa(detached, "ta1@sjsu.edu"), "the section's TA should be recognised")
        assertTrue(courseAccess.isTa(detached, "TA1@sjsu.edu"), "TA matching is case-insensitive")
    }
}
