import com.cs30.server.repository.LoginSessionRepository
import com.cs30.server.service.GitService
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Exercises the real `git` binary against a throwaway repository, because the property under test -
 * that writing course.yml stages only course.yml - is a property of the git commands themselves.
 */
class GitServiceTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var gitService: GitService
    private lateinit var repo: String

    @BeforeEach
    fun setUp() {
        gitService = GitService("docker", "bt", "test@cs30.edu", "CS30 Test", mockk<LoginSessionRepository>(relaxed = true))
        repo = File(tempDir, "students").absolutePath
        gitService.initGitRepo(repo)
    }

    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", repo) + args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output.trim()
    }

    private fun commitCount(): Int =
        git("rev-list", "--count", "HEAD").toIntOrNull() ?: 0

    @Test
    fun `saveTextToRepo writes and commits the file`() {
        gitService.saveTextToRepo(repo, "course.yml", "code: CMPE30\n", "update course.yml")

        assertEquals("code: CMPE30\n", File(repo, "course.yml").readText())
        assertEquals(1, commitCount())
        assertTrue(git("show", "--stat", "--oneline", "HEAD").contains("course.yml"))
    }

    @Test
    fun `saveTextToRepo does not sweep up other pending writes`() {
        // A student submission mid-flight: written, not yet committed by its own code path.
        File(repo, "student-submission.java").writeText("class Main {}")

        gitService.saveTextToRepo(repo, "course.yml", "code: CMPE30\n", "update course.yml")

        val committed = git("show", "--name-only", "--format=", "HEAD").lines().filter { it.isNotBlank() }
        assertEquals(listOf("course.yml"), committed)
        assertTrue(git("status", "--porcelain").contains("student-submission.java"))
    }

    @Test
    fun `rewriting identical content does not create an empty commit`() {
        gitService.saveTextToRepo(repo, "course.yml", "code: CMPE30\n", "update course.yml")
        gitService.saveTextToRepo(repo, "course.yml", "code: CMPE30\n", "update course.yml")

        assertEquals(1, commitCount())
    }

    @Test
    fun `removeFileFromRepo deletes and commits`() {
        gitService.saveTextToRepo(repo, "course.yml", "code: CMPE30\n", "update course.yml")

        gitService.removeFileFromRepo(repo, "course.yml", "course removed")

        assertFalse(File(repo, "course.yml").exists())
        assertEquals(2, commitCount())
    }
}
