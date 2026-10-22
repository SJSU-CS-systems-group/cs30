import com.cs30.server.dto.SubmitCodeRequest
import com.cs30.server.dto.SubmitCodeResponse
import com.cs30.server.models.Course
import com.cs30.server.models.Problem
import com.cs30.server.models.ScheduledLab
import com.cs30.server.repository.CourseRepository
import com.cs30.server.service.CodeService
import com.cs30.server.service.LabAutoSubmitService
import data.SubmissionInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset

class LabAutoSubmitServiceTest {

    private lateinit var courseRepository: CourseRepository
    private lateinit var codeService: CodeService
    private lateinit var service: LabAutoSubmitService

    @TempDir
    lateinit var studentRepo: File

    @BeforeEach
    fun setUp() {
        courseRepository = mockk()
        codeService = mockk()
        service = LabAutoSubmitService(courseRepository, codeService, LOOKBACK_HOURS)
        every { codeService.listSubmissions(any(), any(), any(), any(), any()) } returns emptyList()
        every { codeService.autoSubmit(any(), any()) } returns SubmitCodeResponse(true, "ok")
    }

    /** One student, one problem; the lab's window is [start, end] relative to now in hours. */
    private fun courseWithLab(startHoursFromNow: Long, endHoursFromNow: Long): Course {
        val now = LocalDateTime.now(ZoneOffset.UTC)
        val course = Course(
            id = "course-1", code = "CS-101", section = 1, language = "Java",
            studentGitRepo = studentRepo.absolutePath, problemGitRepo = "/path/to/problems",
        )
        course.students.add(STUDENT)
        val lab = ScheduledLab(
            labNumber = 1,
            startDateTime = now.plusHours(startHoursFromNow),
            endDateTime = now.plusHours(endHoursFromNow),
        )
        lab.addProblem(Problem(name = PROBLEM, language = "Python"))
        course.addLab(lab)
        every { courseRepository.findAllWithStudents() } returns listOf(course)
        return course
    }

    private fun writeAutosave(code: String) {
        File(studentRepo, "section_1/lab_1/$PROBLEM/$STUDENT").apply { mkdirs() }
            .resolve("autosaved-solution.py").writeText(code)
    }

    @Test
    fun `submits the autosave when it differs from the latest submission`() {
        courseWithLab(startHoursFromNow = -3, endHoursFromNow = -1)
        writeAutosave("print('new')")
        every { codeService.listSubmissions(any(), any(), any(), any(), any()) } returns
            listOf(SubmissionInfo(timestamp = "t", passed = 0, total = 1, maxTimeMs = null, status = "WA", filePath = "", code = "print('old')"))
        val request = slot<SubmitCodeRequest>()
        every { codeService.autoSubmit(any(), capture(request)) } returns SubmitCodeResponse(true, "ok")

        service.submitEndedLabs()

        assertEquals("print('new')", request.captured.code)
        assertEquals(STUDENT, request.captured.studentEmail)
        assertEquals("Python", request.captured.language)
        // The lab is handled once; a second pass does not rescan it.
        service.submitEndedLabs()
        verify(exactly = 1) { codeService.autoSubmit(any(), any()) }
    }

    @Test
    fun `skips the student when the autosave was already submitted`() {
        courseWithLab(startHoursFromNow = -3, endHoursFromNow = -1)
        writeAutosave("print('same')")
        every { codeService.listSubmissions(any(), any(), any(), any(), any()) } returns
            listOf(SubmissionInfo(timestamp = "t", passed = 1, total = 1, maxTimeMs = null, status = "AC", filePath = "", code = "print('same')"))

        service.submitEndedLabs()

        verify(exactly = 0) { codeService.autoSubmit(any(), any()) }
    }

    @Test
    fun `ignores labs that are still active or ended before the lookback window`() {
        courseWithLab(startHoursFromNow = -1, endHoursFromNow = 1)
        writeAutosave("print('x')")
        service.submitEndedLabs()

        courseWithLab(startHoursFromNow = -(LOOKBACK_HOURS + 3), endHoursFromNow = -(LOOKBACK_HOURS + 1))
        service.submitEndedLabs()

        verify(exactly = 0) { codeService.autoSubmit(any(), any()) }
    }

    companion object {
        private const val STUDENT = "student@sjsu.edu"
        private const val PROBLEM = "hello-world"
        private const val LOOKBACK_HOURS = 24L
    }
}
