import com.cs30.server.models.Course
import com.cs30.server.service.CourseYamlService
import com.cs30.server.service.CourseYamlSyncService
import com.cs30.server.service.CourseYamlSyncSettings
import com.cs30.server.service.CourseYamlTarget
import com.cs30.server.service.GitService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

class CourseYamlSyncServiceTest {

    private lateinit var courseYamlService: CourseYamlService
    private lateinit var gitService: GitService

    private val target = CourseYamlTarget("CMPE30", 2026, "Fall", "/repos/students")

    private fun settings(enabled: Boolean = true, removeOnEmpty: Boolean = true) =
        CourseYamlSyncSettings(enabled, "course.yml", "update course.yml", removeOnEmpty)

    private fun service(settings: CourseYamlSyncSettings = settings()) =
        CourseYamlSyncService(courseYamlService, gitService, settings)

    @BeforeEach
    fun setUp() {
        courseYamlService = mockk(relaxed = true)
        gitService = mockk(relaxed = true)
        every { gitService.repositoryExists(any()) } returns true
        every { courseYamlService.findSections(any(), any(), any()) } returns listOf(mockk<Course>(relaxed = true))
        every { courseYamlService.render(any()) } returns "code: CMPE30\n"
    }

    @AfterEach
    fun tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    /** Runs [block] as if inside a transaction, then completes it with [status]. */
    private fun inTransaction(status: Int = TransactionSynchronization.STATUS_COMMITTED, block: () -> Unit) {
        TransactionSynchronizationManager.initSynchronization()
        try {
            block()
            TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCompletion(status) }
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    @Test
    fun `repeated requests in one transaction produce a single write`() {
        val service = service()

        inTransaction {
            service.requestSync(target)
            service.requestSync(target)
            service.requestSync(target)
            // Nothing may be written before the transaction completes.
            verify(exactly = 0) { gitService.saveTextToRepo(any(), any(), any(), any()) }
        }

        verify(exactly = 1) { gitService.saveTextToRepo("/repos/students", "course.yml", "code: CMPE30\n", "update course.yml") }
    }

    @Test
    fun `a rolled back transaction writes nothing`() {
        val service = service()

        inTransaction(TransactionSynchronization.STATUS_ROLLED_BACK) {
            service.requestSync(target)
        }

        verify(exactly = 0) { gitService.saveTextToRepo(any(), any(), any(), any()) }
    }

    @Test
    fun `a rolled back transaction leaves no pending state behind for the next one`() {
        val service = service()

        inTransaction(TransactionSynchronization.STATUS_ROLLED_BACK) {
            service.requestSync(target)
        }
        // Same thread, next transaction: the rolled-back request must not reappear.
        inTransaction {
            service.requestSync(CourseYamlTarget("CMPE30", 2026, "Spring", "/repos/spring"))
        }

        verify(exactly = 0) { gitService.saveTextToRepo("/repos/students", any(), any(), any()) }
        verify(exactly = 1) { gitService.saveTextToRepo("/repos/spring", any(), any(), any()) }
    }

    @Test
    fun `a caller with no transaction is written immediately rather than dropped`() {
        service().requestSync(target)

        verify(exactly = 1) { gitService.saveTextToRepo("/repos/students", "course.yml", any(), any()) }
    }

    @Test
    fun `a git failure never escapes to the caller`() {
        every { gitService.saveTextToRepo(any(), any(), any(), any()) } throws RuntimeException("git lock timeout")
        val service = service()

        // The database change has already committed; throwing here would fail the TA's request for
        // a student who was, in fact, added.
        TransactionSynchronizationManager.initSynchronization()
        try {
            service.requestSync(target)
            TransactionSynchronizationManager.getSynchronizations()
                .forEach { it.afterCompletion(TransactionSynchronization.STATUS_COMMITTED) }
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    @Test
    fun `deleting the last section removes the file`() {
        every { courseYamlService.findSections(any(), any(), any()) } returns emptyList()

        inTransaction { service().requestSync(target) }

        verify(exactly = 1) { gitService.removeFileFromRepo("/repos/students", "course.yml", any()) }
        verify(exactly = 0) { gitService.saveTextToRepo(any(), any(), any(), any()) }
    }

    // ==================== ensureCurrent (lab health check) ====================

    private fun courseIn(repo: java.io.File): Course {
        val course = mockk<Course>(relaxed = true)
        every { course.code } returns "CMPE30"
        every { course.year } returns 2026
        every { course.semester } returns "Fall"
        every { course.studentGitRepo } returns repo.absolutePath
        return course
    }

    @Test
    fun `a current file is left alone`(@org.junit.jupiter.api.io.TempDir repo: java.io.File) {
        java.io.File(repo, "course.yml").writeText("code: CMPE30\n")

        val message = service().ensureCurrent(courseIn(repo))

        assertEquals(null, message)
        verify(exactly = 0) { gitService.saveTextToRepo(any(), any(), any(), any()) }
    }

    @Test
    fun `a stale file is regenerated and reported`(@org.junit.jupiter.api.io.TempDir repo: java.io.File) {
        java.io.File(repo, "course.yml").writeText("code: HAND-EDITED\n")

        val message = service().ensureCurrent(courseIn(repo))

        assertEquals("course.yml was stale - regenerated and committed", message)
        verify(exactly = 1) { gitService.saveTextToRepo(repo.absolutePath, "course.yml", "code: CMPE30\n", any()) }
    }

    @Test
    fun `a failed repair is reported, never thrown`(@org.junit.jupiter.api.io.TempDir repo: java.io.File) {
        every { gitService.saveTextToRepo(any(), any(), any(), any()) } throws RuntimeException("git lock timeout")

        val message = service().ensureCurrent(courseIn(repo))

        assertEquals("course.yml is stale and could not be regenerated: git lock timeout", message)
    }
}
