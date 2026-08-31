package com.cs30.server.controller

import com.cs30.server.models.Course
import com.cs30.server.models.Problem
import com.cs30.server.models.ScheduledLab
import com.cs30.server.repository.CourseRepository
import com.cs30.server.service.AppTimeZoneService
import com.cs30.server.service.CourseAccessService
import com.cs30.server.service.GitService
import com.cs30.server.service.StudentIdentityService
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.hamcrest.Matchers.greaterThanOrEqualTo
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Optional

/**
 * The student-app lab endpoints (`LabController`, `AutosaveController`) as seen by the two kinds of
 * member. A student gets the active labs, a countdown, and a 403 on autosave outside the window.
 * The course's TA gets every lab, no countdown at all (`remainingMs: null`, which the editor reads
 * as "no timer chip" rather than "time's up"), and can autosave and restore outside the window —
 * the autosave gate is the one that would otherwise silently break a TA's practice session.
 *
 * Both controllers live in one slice on purpose: same mocks, same question (student vs TA).
 */
@WebMvcTest
@ContextConfiguration(classes = [LabController::class, AutosaveController::class, LabAccessControllerTest.Mocks::class])
@AutoConfigureMockMvc(addFilters = false)
class LabAccessControllerTest {

    @TestConfiguration
    class Mocks {
        @Bean fun courseRepository(): CourseRepository = mockk()
        @Bean fun studentIdentityService(): StudentIdentityService = mockk()
        @Bean fun appTimeZoneService(): AppTimeZoneService = mockk()
        @Bean fun gitService(): GitService = mockk(relaxed = true)
        @Bean fun courseAccessService(courseRepository: CourseRepository) = CourseAccessService(courseRepository)
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var courseRepository: CourseRepository
    @Autowired lateinit var identity: StudentIdentityService
    @Autowired lateinit var appTimeZoneService: AppTimeZoneService
    @Autowired lateinit var gitService: GitService

    private val student = "student@sjsu.edu"
    private val ta = "ta@sjsu.edu"
    private val stranger = "stranger@sjsu.edu"

    private fun now(): LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)

    /** One section: the student on the roster, the TA assigned, and a past (1), an active (2) and a future (3) lab. */
    private val course = Course(
        id = "course-1", code = "CS30", section = 1, year = 2026, semester = "Spring",
        language = "python", studentGitRepo = "/repo", taEmail = ta,
    ).apply {
        students.add(student)
        addLab(ScheduledLab(labNumber = 1, startDateTime = now().minusHours(3), endDateTime = now().minusHours(1)).apply {
            addProblem(Problem(name = "hello-world", language = "python"))
        })
        addLab(ScheduledLab(labNumber = 2, startDateTime = now().minusHours(1), endDateTime = now().plusHours(1)))
        addLab(ScheduledLab(labNumber = 3, startDateTime = now().plusHours(1), endDateTime = now().plusHours(3)))
    }

    @BeforeEach
    fun reset() {
        clearMocks(courseRepository, identity, appTimeZoneService, gitService)
        every { identity.resolve(null) } returns null
        every { identity.resolve("Bearer student") } returns student
        every { identity.resolve("Bearer ta") } returns ta
        every { identity.resolve("Bearer stranger") } returns stranger
        every { appTimeZoneService.toAppZone(any<LocalDateTime>()) } answers { firstArg() }
        every { courseRepository.findById("course-1") } returns Optional.of(course)
        every { courseRepository.findByStudentEmail(any()) } returns emptyList()
        every { courseRepository.findByTaEmail(any()) } returns emptyList()
        every { courseRepository.findByStudentEmail(student) } returns listOf(course)
        every { courseRepository.findByTaEmail(ta) } returns listOf(course)
        every { courseRepository.existsByIdAndStudentsContaining(any(), any()) } returns false
        every { courseRepository.existsByIdAndStudentsContaining("course-1", student) } returns true
    }

    // ==================== GET /api/labs/student ====================

    @Test
    fun `student lab list is only the active labs for a student and every lab for the TA`() {
        mvc.get("/api/labs/student") { header("Authorization", "Bearer student") }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].labNumber") { value(2) }
        }
        mvc.get("/api/labs/student") { header("Authorization", "Bearer ta") }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(3) }
        }
    }

    @Test
    fun `student lab list is 401 without a token and 404 for an email that is neither student nor TA`() {
        mvc.get("/api/labs/student").andExpect { status { isUnauthorized() } }
        mvc.get("/api/labs/student") { header("Authorization", "Bearer stranger") }.andExpect {
            status { isNotFound() }
        }
    }

    // ==================== GET /api/labs/{courseId}/lab/{labNumber}/remaining ====================

    @Test
    fun `remaining is a countdown for a student and null for the course TA`() {
        mvc.get("/api/labs/course-1/lab/2/remaining") { header("Authorization", "Bearer student") }.andExpect {
            status { isOk() }
            jsonPath("$.remainingMs", greaterThanOrEqualTo(0))
        }
        // A past lab: the student's countdown is clamped to zero, the TA still gets no countdown.
        mvc.get("/api/labs/course-1/lab/1/remaining") { header("Authorization", "Bearer student") }.andExpect {
            status { isOk() }
            jsonPath("$.remainingMs") { value(0) }
        }
        mvc.get("/api/labs/course-1/lab/1/remaining") { header("Authorization", "Bearer ta") }.andExpect {
            status { isOk() }
            jsonPath("$.remainingMs", nullValue())
        }
    }

    // ==================== POST /api/autosave, GET /api/autosave/... ====================

    private fun autosave(labNumber: Int, token: String?) = mvc.post("/api/autosave") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"courseId":"course-1","section":1,"labNumber":$labNumber,"problemSlug":"hello-world","code":"print(1)","language":"python"}"""
        if (token != null) header("Authorization", token)
    }

    @Test
    fun `autosave is 401 without a token`() {
        autosave(1, null).andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `TA can autosave outside the lab window`() {
        autosave(1, "Bearer ta").andExpect { status { isAccepted() } }

        verify(exactly = 1) {
            gitService.saveAutosolution(
                repoPath = "/repo", section = 1, labNumber = 1, problemName = "hello-world",
                studentEmail = ta, code = "print(1)", extension = "py", authorEmail = ta,
            )
        }
    }

    @Test
    fun `student is refused outside the lab window but not inside it`() {
        autosave(1, "Bearer student").andExpect { status { isForbidden() } }
        verify(exactly = 0) { gitService.saveAutosolution(any(), any(), any(), any(), any(), any(), any(), any()) }

        autosave(2, "Bearer student").andExpect { status { isAccepted() } }
    }

    @Test
    fun `a stranger is refused even for an active lab`() {
        autosave(2, "Bearer stranger").andExpect { status { isForbidden() } }
    }

    @Test
    fun `TA can read the latest autosave`() {
        every { gitService.readLatestAutosave("/repo", 1, 1, "hello-world", ta, "py") } returns "print(2)"

        mvc.get("/api/autosave/course-1/1/1/hello-world") { header("Authorization", "Bearer ta") }.andExpect {
            status { isOk() }
            content { string("print(2)") }
        }
    }
}
