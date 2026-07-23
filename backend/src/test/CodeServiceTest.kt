import com.cs30.server.dto.RunCodeRequest
import com.cs30.server.dto.SubmitCodeRequest
import com.cs30.server.models.Course
import com.cs30.server.models.Problem
import com.cs30.server.models.ScheduledLab
import com.cs30.server.repository.CourseRepository
import com.cs30.server.service.CodeService
import com.cs30.server.service.GitService
import com.cs30.server.service.JudgeService
import com.cs30.server.service.JudgeSubmitResponse
import com.cs30.server.service.JudgeRunResponse
import com.cs30.server.service.JudgeTestcase
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.Optional

class CodeServiceTest {

    private lateinit var courseRepository: CourseRepository
    private lateinit var gitService: GitService
    private lateinit var judgeService: JudgeService
    private lateinit var codeService: CodeService

    @BeforeEach
    fun setUp() {
        courseRepository = mockk(relaxed = true)
        gitService = mockk(relaxed = true)
        judgeService = mockk(relaxed = true)
        codeService = CodeService(courseRepository, gitService, judgeService)
        // Enrollment is checked via the repo (existsByIdAndStudentsContaining), not course.students —
        // the enrolled student passes; "unenrolled@sjsu.edu" falls through to the relaxed default (false).
        every { courseRepository.existsByIdAndStudentsContaining(any(), "student@sjsu.edu") } returns true
    }

    private fun createActiveCourse(): Course {
        val course = Course(
            id = "course-1",
            code = "CS-101",
            section = 1,
            year = 2024,
            semester = "Fall",
            language = "Java",
            studentGitRepo = "/path/to/students",
            problemGitRepo = "/path/to/problems"
        )
        course.students.add("student@sjsu.edu")

        val lab = ScheduledLab(
            labNumber = 1,
            startDateTime = LocalDateTime.now().minusHours(1),
            endDateTime = LocalDateTime.now().plusHours(1)
        )
        lab.addProblem(Problem(name = "hello-world", language = "Java"))
        course.addLab(lab)

        every { courseRepository.existsByIdAndStudentsContaining(course.id, "student@sjsu.edu") } returns true

        return course
    }

    // ==================== submitCode tests ====================

    @Test
    fun `submitCode should return error when course not found`() {
        every { courseRepository.findById("invalid-course") } returns Optional.empty()

        val request = SubmitCodeRequest(
            courseId = "invalid-course",
            section = 1,
            labNumber = 1,
            problemName = "hello-world",
            studentEmail = "student@sjsu.edu",
            code = "public class Main {}"
        )

        val response = codeService.submitCode(request)

        assertFalse(response.success)
        assertTrue(response.message.contains("Course not found"))
    }

    @Test
    fun `submitCode should return error when student not enrolled`() {
        val course = createActiveCourse()
        every { courseRepository.findById("course-1") } returns Optional.of(course)

        val request = SubmitCodeRequest(
            courseId = "course-1",
            section = 1,
            labNumber = 1,
            problemName = "hello-world",
            studentEmail = "unenrolled@sjsu.edu",
            code = "public class Main {}"
        )

        val response = codeService.submitCode(request)

        assertFalse(response.success)
        assertTrue(response.message.contains("not enrolled"))
    }

    @Test
    fun `submitCode should return error when lab not found`() {
        val course = createActiveCourse()
        every { courseRepository.findById("course-1") } returns Optional.of(course)

        val request = SubmitCodeRequest(
            courseId = "course-1",
            section = 1,
            labNumber = 99,
            problemName = "hello-world",
            studentEmail = "student@sjsu.edu",
            code = "public class Main {}"
        )

        val response = codeService.submitCode(request)

        assertFalse(response.success)
        assertTrue(response.message.contains("Lab 99 not found"))
    }

    @Test
    fun `submitCode should return error when lab deadline passed`() {
        val course = createActiveCourse()
        // Set lab to be expired
        course.labs[0].endDateTime = LocalDateTime.now().minusHours(1)
        every { courseRepository.findById("course-1") } returns Optional.of(course)

        val request = SubmitCodeRequest(
            courseId = "course-1",
            section = 1,
            labNumber = 1,
            problemName = "hello-world",
            studentEmail = "student@sjsu.edu",
            code = "public class Main {}"
        )

        val response = codeService.submitCode(request)

        assertFalse(response.success)
        assertTrue(response.message.contains("deadline has passed"))
    }

    @Test
    fun `submitCode should return error when lab has not started`() {
        val course = createActiveCourse()
        // Set lab to start in the future
        course.labs[0].startDateTime = LocalDateTime.now().plusHours(1)
        course.labs[0].endDateTime = LocalDateTime.now().plusHours(2)
        every { courseRepository.findById("course-1") } returns Optional.of(course)

        val request = SubmitCodeRequest(
            courseId = "course-1",
            section = 1,
            labNumber = 1,
            problemName = "hello-world",
            studentEmail = "student@sjsu.edu",
            code = "public class Main {}"
        )

        val response = codeService.submitCode(request)

        assertFalse(response.success)
        assertTrue(response.message.contains("not started"))
    }

    @Test
    fun `submitCode should return error when git repo not configured`() {
        val course = createActiveCourse()
        course.studentGitRepo = ""
        every { courseRepository.findById("course-1") } returns Optional.of(course)

        val request = SubmitCodeRequest(
            courseId = "course-1",
            section = 1,
            labNumber = 1,
            problemName = "hello-world",
            studentEmail = "student@sjsu.edu",
            code = "public class Main {}"
        )

        val response = codeService.submitCode(request)

        assertFalse(response.success)
        assertTrue(response.message.contains("Git repository"))
    }

    @Test
    fun `submitCode should succeed with judge result`() {
        val course = createActiveCourse()
        every { courseRepository.findById("course-1") } returns Optional.of(course)
        every { judgeService.submit(any(), any(), any(), any(), any()) } returns JudgeSubmitResponse(
            status = "AC",
            passed = 3,
            total = 3,
            maxTimeS = 0.1,
            testcases = listOf(
                JudgeTestcase("test1", "AC", 0.01, "1", "1", "1", null)
            ),
            compileOutput = null
        )
        every { gitService.saveSubmissionWithResult(any(), any(), any(), any(), any(), any(), any(), any()) } returns "/path/to/submission.java"

        val request = SubmitCodeRequest(
            courseId = "course-1",
            section = 1,
            labNumber = 1,
            problemName = "hello-world",
            studentEmail = "student@sjsu.edu",
            code = "public class Main {}"
        )

        val response = codeService.submitCode(request)

        assertTrue(response.success)
        assertEquals("AC", response.status)
        assertEquals(3, response.passed)
        assertEquals(3, response.total)
    }

    @Test
    fun `submitCode should prevent concurrent submissions from same student`() {
        val course = createActiveCourse()
        every { courseRepository.findById("course-1") } returns Optional.of(course)
        // Make judge service block
        every { judgeService.submit(any(), any(), any(), any(), any()) } answers {
            Thread.sleep(100)
            JudgeSubmitResponse("AC", 1, 1, 0.1, emptyList(), null)
        }
        every { gitService.saveSubmissionWithResult(any(), any(), any(), any(), any(), any(), any(), any()) } returns "/path"

        val request = SubmitCodeRequest(
            courseId = "course-1",
            section = 1,
            labNumber = 1,
            problemName = "hello-world",
            studentEmail = "student@sjsu.edu",
            code = "code"
        )

        // Start first submission in background
        val thread = Thread { codeService.submitCode(request) }
        thread.start()
        Thread.sleep(20) // Give first call time to acquire lock

        // Second call should be rejected
        val response2 = codeService.submitCode(request)

        assertFalse(response2.success)
        assertTrue(response2.message.contains("already in progress"))

        thread.join()
    }

    // ==================== runCode tests ====================

    @Test
    fun `runCode should return error when course not found`() {
        every { courseRepository.findById("invalid-course") } returns Optional.empty()

        val request = RunCodeRequest(
            courseId = "invalid-course",
            section = 1,
            labNumber = 1,
            problemName = "hello-world",
            studentEmail = "student@sjsu.edu",
            code = "print('hello')"
        )

        val response = codeService.runCode(request)

        assertFalse(response.success)
        assertTrue(response.message.contains("Course not found"))
    }

    @Test
    fun `runCode should return error when student not enrolled`() {
        val course = createActiveCourse()
        every { courseRepository.findById("course-1") } returns Optional.of(course)

        val request = RunCodeRequest(
            courseId = "course-1",
            section = 1,
            labNumber = 1,
            problemName = "hello-world",
            studentEmail = "unenrolled@sjsu.edu",
            code = "print('hello')"
        )

        val response = codeService.runCode(request)

        assertFalse(response.success)
        assertTrue(response.message.contains("not enrolled"))
    }

    @Test
    fun `runCode should succeed with testcase results`() {
        val course = createActiveCourse()
        every { courseRepository.findById("course-1") } returns Optional.of(course)
        every { judgeService.run(any(), any(), any(), any(), any()) } returns JudgeRunResponse(
            testcases = listOf(
                JudgeTestcase("sample1", "AC", 0.01, "1", "1", "1", null)
            ),
            compileOutput = null
        )

        val request = RunCodeRequest(
            courseId = "course-1",
            section = 1,
            labNumber = 1,
            problemName = "hello-world",
            studentEmail = "student@sjsu.edu",
            code = "print(1)"
        )

        val response = codeService.runCode(request)

        assertTrue(response.success)
        assertNotNull(response.testcases)
        assertEquals(1, response.testcases!!.size)
    }

    @Test
    fun `runCode should handle judge exceptions gracefully`() {
        val course = createActiveCourse()
        every { courseRepository.findById("course-1") } returns Optional.of(course)
        every { judgeService.run(any(), any(), any(), any(), any()) } throws RuntimeException("Judge unavailable")

        val request = RunCodeRequest(
            courseId = "course-1",
            section = 1,
            labNumber = 1,
            problemName = "hello-world",
            studentEmail = "student@sjsu.edu",
            code = "print(1)"
        )

        val response = codeService.runCode(request)

        assertFalse(response.success)
        assertTrue(response.message.contains("Something went wrong"))
    }

    // ==================== listSubmissions tests ====================

    @Test
    fun `listSubmissions should return empty list when course not found`() {
        every { courseRepository.findById("invalid-course") } returns Optional.empty()

        val result = codeService.listSubmissions("invalid-course", 1, 1, "problem", "student@sjsu.edu")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `listSubmissions should return empty list when student not enrolled`() {
        val course = createActiveCourse()
        every { courseRepository.findById("course-1") } returns Optional.of(course)

        val result = codeService.listSubmissions("course-1", 1, 1, "problem", "unenrolled@sjsu.edu")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `listSubmissions should return empty list when git repo not configured`() {
        val course = createActiveCourse()
        course.studentGitRepo = ""
        every { courseRepository.findById("course-1") } returns Optional.of(course)

        val result = codeService.listSubmissions("course-1", 1, 1, "problem", "student@sjsu.edu")

        assertTrue(result.isEmpty())
    }
}