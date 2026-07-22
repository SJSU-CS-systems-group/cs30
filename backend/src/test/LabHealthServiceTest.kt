import com.cs30.server.models.Course
import com.cs30.server.models.Problem
import com.cs30.server.models.ScheduledLab
import com.cs30.server.repository.CourseRepository
import com.cs30.server.service.GitService
import com.cs30.server.service.JudgeService
import com.cs30.server.dto.ProblemStatus
import com.cs30.server.service.JudgeSubmitResponse
import com.cs30.server.service.LabHealthService
import com.cs30.server.service.ProblemFiles
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Optional

class LabHealthServiceTest {

    private lateinit var courseRepository: CourseRepository
    private lateinit var gitService: GitService
    private lateinit var judgeService: JudgeService
    private lateinit var service: LabHealthService

    private val repo = "/tmp/problems"

    @BeforeEach
    fun setUp() {
        courseRepository = mockk(relaxed = true)
        gitService = mockk(relaxed = true)
        judgeService = mockk(relaxed = true)
        service = LabHealthService(courseRepository, gitService, judgeService)
    }

    private fun course(vararg problemNames: String): Course {
        val lab = ScheduledLab(labNumber = 1)
        problemNames.forEach { lab.addProblem(Problem(name = it, language = "python")) }
        return Course(id = "c1", problemGitRepo = repo).apply { addLab(lab) }
    }

    private fun acceptedFile(ext: String = "py"): File =
        File.createTempFile("accepted", ".$ext").apply { writeText("print(1)"); deleteOnExit() }

    private fun allPresent(accepted: File?) =
        ProblemFiles(present = true, html = true, css = true, problemYaml = true, data = true,
            acceptedSolution = accepted, hasAnyAcceptedSolution = accepted != null)

    @Test
    fun `returns not-ok when course is missing`() {
        every { courseRepository.findById("c1") } returns Optional.empty()

        val report = service.checkLab("c1", 1)

        assertFalse(report.ok)
        assertTrue(report.detail!!.contains("Course not found"))
        assertTrue(report.problems.isEmpty())
    }

    @Test
    fun `returns not-ok when problem git repo is blank`() {
        every { courseRepository.findById("c1") } returns Optional.of(Course(id = "c1", problemGitRepo = ""))

        val report = service.checkLab("c1", 1)

        assertFalse(report.ok)
        assertTrue(report.detail!!.contains("problem git repository"))
    }

    @Test
    fun `returns not-ok when lab is missing`() {
        every { courseRepository.findById("c1") } returns Optional.of(course("p1"))

        val report = service.checkLab("c1", 99)

        assertFalse(report.ok)
        assertTrue(report.detail!!.contains("Lab 99 not found"))
    }

    @Test
    fun `returns ok for a lab with no problems without touching the judge`() {
        val emptyLab = Course(id = "c1", problemGitRepo = repo).apply { addLab(ScheduledLab(labNumber = 1)) }
        every { courseRepository.findById("c1") } returns Optional.of(emptyLab)

        val report = service.checkLab("c1", 1)

        assertTrue(report.ok)
        assertTrue(report.judgeReachable)
        assertTrue(report.judgeReady)
        assertTrue(report.problems.isEmpty())
        verify(exactly = 0) { judgeService.submit(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `grades accepted solution and reports ok on AC`() {
        val accepted = acceptedFile()
        every { courseRepository.findById("c1") } returns Optional.of(course("p1"))
        every { gitService.problemFilesReady(repo, "p1", any()) } returns allPresent(accepted)
        every { judgeService.submit("p1", repo, "python", any(), any()) } returns
            JudgeSubmitResponse(status = "AC", passed = 3, total = 3, maxTimeS = 0.1, testcases = emptyList(), compileOutput = null)

        val report = service.checkLab("c1", 1)

        assertTrue(report.ok)
        assertTrue(report.judgeReachable)
        assertTrue(report.judgeReady)
        val p = report.problems.single()
        assertEquals(ProblemStatus.READY, p.status)
        assertEquals("AC", p.verdict)
        assertEquals(3, p.passed)
        assertEquals(3, p.total)
    }

    @Test
    fun `reports unverified when files present but no accepted solution`() {
        every { courseRepository.findById("c1") } returns Optional.of(course("p1"))
        every { gitService.problemFilesReady(repo, "p1", any()) } returns allPresent(null)

        val report = service.checkLab("c1", 1)

        assertTrue(report.ok)   // unverified is a warning, not a hard failure
        val p = report.problems.single()
        assertEquals(ProblemStatus.UNVERIFIED, p.status)
        assertFalse(p.acceptedSolutionPresent)
        assertTrue(p.packagePresent)
        assertTrue(p.detail!!.contains("No accepted solution in submissions/accepted/"))
        assertTrue(report.errors.isEmpty())
        assertTrue(report.warnings.any { it.startsWith("p1:") })
        verify(exactly = 0) { judgeService.submit(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `reports unverified when accepted solutions exist but none in the configured language`() {
        // babyshark's exact case: has .py/.cpp reference solutions, but the problem is configured Java.
        every { courseRepository.findById("c1") } returns Optional.of(course("p1"))
        every { gitService.problemFilesReady(repo, "p1", any()) } returns
            ProblemFiles(present = true, html = true, css = true, problemYaml = true, data = true,
                acceptedSolution = null, hasAnyAcceptedSolution = true)

        val report = service.checkLab("c1", 1)

        assertTrue(report.ok)   // unverified is a warning, not a hard failure
        val p = report.problems.single()
        assertEquals(ProblemStatus.UNVERIFIED, p.status)
        assertTrue(p.acceptedSolutionPresent)
        assertTrue(p.detail!!.contains("none for the configured language"))
        assertTrue(report.warnings.any { it.contains("none for the configured language") })
        verify(exactly = 0) { judgeService.submit(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `reports not-in-pool error when the problem dir is absent`() {
        every { courseRepository.findById("c1") } returns Optional.of(course("p1"))
        every { gitService.problemFilesReady(repo, "p1", any()) } returns
            ProblemFiles(present = false, html = false, css = false, problemYaml = false, data = false,
                acceptedSolution = null, hasAnyAcceptedSolution = false)

        val report = service.checkLab("c1", 1)

        assertFalse(report.ok)
        val p = report.problems.single()
        assertEquals(ProblemStatus.NOT_READY, p.status)
        assertTrue(p.detail!!.contains("not found in the pool"))
        assertTrue(report.errors.any { it.contains("not found in the pool") })
        verify(exactly = 0) { judgeService.submit(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `reports not-ok when accepted solution does not grade AC`() {
        val accepted = acceptedFile()
        every { courseRepository.findById("c1") } returns Optional.of(course("p1"))
        every { gitService.problemFilesReady(repo, "p1", any()) } returns allPresent(accepted)
        every { judgeService.submit("p1", repo, "python", any(), any()) } returns
            JudgeSubmitResponse(status = "WA", passed = 2, total = 3, maxTimeS = 0.1, testcases = emptyList(), compileOutput = null)

        val report = service.checkLab("c1", 1)

        assertFalse(report.ok)
        val p = report.problems.single()
        assertEquals(ProblemStatus.NOT_READY, p.status)
        assertEquals("WA", p.verdict)
        assertTrue(p.detail!!.contains("WA"))
    }

    @Test
    fun `skips grading and reports missing package when problem yaml is absent`() {
        every { courseRepository.findById("c1") } returns Optional.of(course("p1"))
        every { gitService.problemFilesReady(repo, "p1", any()) } returns
            ProblemFiles(present = true, html = true, css = true, problemYaml = false, data = false,
                acceptedSolution = null, hasAnyAcceptedSolution = false)

        val report = service.checkLab("c1", 1)

        assertFalse(report.ok)
        val p = report.problems.single()
        assertEquals(ProblemStatus.NOT_READY, p.status)
        assertFalse(p.packagePresent)
        assertTrue(p.detail!!.contains("problem.yaml"))
        assertTrue(report.errors.any { it.contains("problem.yaml") })
        verify(exactly = 0) { judgeService.submit(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `marks judge unreachable when submit throws a connection error`() {
        val accepted = acceptedFile()
        every { courseRepository.findById("c1") } returns Optional.of(course("p1"))
        every { gitService.problemFilesReady(repo, "p1", any()) } returns allPresent(accepted)
        every { judgeService.submit("p1", repo, "python", any(), any()) } throws
            java.net.ConnectException("Connection refused")

        val report = service.checkLab("c1", 1)

        assertFalse(report.ok)
        assertFalse(report.judgeReachable)
        assertEquals(ProblemStatus.NOT_READY, report.problems.single().status)
    }

    @Test
    fun `marks judge not ready when submit fails with a 503 docker error`() {
        val accepted = acceptedFile()
        every { courseRepository.findById("c1") } returns Optional.of(course("p1"))
        every { gitService.problemFilesReady(repo, "p1", any()) } returns allPresent(accepted)
        every { judgeService.submit("p1", repo, "python", any(), any()) } throws
            RuntimeException("Judge error (503): docker unavailable or image not found")

        val report = service.checkLab("c1", 1)

        assertFalse(report.ok)
        assertFalse(report.judgeReady)
    }
}
