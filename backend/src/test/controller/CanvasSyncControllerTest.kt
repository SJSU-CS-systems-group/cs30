package com.cs30.server.controller

import com.cs30.server.dto.BestSubmission
import com.cs30.server.dto.CanvasLabPlan
import com.cs30.server.dto.CanvasProblemPlan
import com.cs30.server.dto.CourseQuery
import com.cs30.server.dto.CourseRef
import com.cs30.server.dto.StudentBestSubmission
import com.cs30.server.models.CliToken
import com.cs30.server.models.CliTokenRole
import com.cs30.server.models.Course
import com.cs30.server.repository.CourseRepository
import com.cs30.server.service.CanvasSyncService
import com.cs30.server.service.CliTokenService
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.LocalDateTime

/**
 * The contract the remote CLI relies on: who gets in (admin anywhere, a TA only on their own
 * section, nobody else), how misses are reported, and the wire format of the plan - the CLI
 * parses `startDateTime` back into a LocalDateTime, so its shape is pinned here.
 */
@WebMvcTest
@ContextConfiguration(classes = [CanvasSyncController::class, CanvasSyncControllerTest.Mocks::class])
@AutoConfigureMockMvc(addFilters = false)
class CanvasSyncControllerTest {

    @TestConfiguration
    class Mocks {
        @Bean fun cliTokenService(): CliTokenService = mockk()
        @Bean fun canvasSyncService(): CanvasSyncService = mockk()
        @Bean fun courseRepository(): CourseRepository = mockk()
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var cliTokenService: CliTokenService
    @Autowired lateinit var canvasSyncService: CanvasSyncService
    @Autowired lateinit var courseRepository: CourseRepository

    private val admin = CliToken(email = "admin@sjsu.edu", role = CliTokenRole.ADMIN)
    private val ta = CliToken(email = "ta@sjsu.edu", role = CliTokenRole.TA)
    private val professor = CliToken(email = "prof@sjsu.edu", role = CliTokenRole.PROFESSOR)

    private val plan = CanvasLabPlan(
        courseCode = "CS30", section = 1, labNumber = 1,
        startDateTime = LocalDateTime.of(2026, 2, 10, 10, 0),
        endDateTime = LocalDateTime.of(2026, 2, 10, 11, 15),
        problems = listOf(CanvasProblemPlan("babyshark", "Bonus")),
        studentEmails = listOf("a@sjsu.edu"),
    )

    private val labQuery = "code=CS30&year=2026&semester=Spring&section=1&lab=1"

    @BeforeEach
    fun reset() {
        clearMocks(cliTokenService, canvasSyncService, courseRepository)
        every { cliTokenService.resolveAuthorization(null) } returns null
        every { cliTokenService.resolveAuthorization("Bearer admin") } returns admin
        every { cliTokenService.resolveAuthorization("Bearer ta") } returns ta
        every { cliTokenService.resolveAuthorization("Bearer prof") } returns professor
        every { cliTokenService.resolveAuthorization("Bearer junk") } returns null
    }

    /** The courses the TA token's email is assigned to, as taEmail lookups see them. */
    private fun taAssignedTo(vararg sections: Int) {
        every { courseRepository.findByTaEmail("ta@sjsu.edu") } returns sections.map {
            Course(code = "CS30", section = it, year = 2026, semester = "Spring", taEmail = "ta@sjsu.edu")
        }
    }

    @Test
    fun `no token is 401`() {
        mvc.get("/api/admin/canvas/lab?$labQuery").andExpect {
            status { isUnauthorized() }
            jsonPath("$.error") { value("Valid CLI token required") }
        }
        mvc.get("/api/admin/canvas/lab?$labQuery") { header("Authorization", "Bearer junk") }.andExpect {
            status { isUnauthorized() }
        }
        verify(exactly = 0) { canvasSyncService.labPlan(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a professor token is refused`() {
        mvc.get("/api/admin/canvas/lab?$labQuery") { header("Authorization", "Bearer prof") }.andExpect {
            status { isForbidden() }
            jsonPath("$.error") { value("Only the admin or the section's TA can use this") }
        }
    }

    @Test
    fun `a TA token only reaches its own section`() {
        taAssignedTo(2)

        mvc.get("/api/admin/canvas/lab?$labQuery") { header("Authorization", "Bearer ta") }.andExpect {
            status { isForbidden() }
            jsonPath("$.error") { value("This token is not the TA for CS30 section 1 (Spring 2026)") }
        }
        verify(exactly = 0) { canvasSyncService.labPlan(any(), any(), any(), any(), any()) }

        taAssignedTo(1, 2)
        every { canvasSyncService.labPlan("CS30", 2026, "Spring", 1, 1) } returns plan

        mvc.get("/api/admin/canvas/lab?$labQuery") { header("Authorization", "Bearer ta") }.andExpect {
            status { isOk() }
            jsonPath("$.courseCode") { value("CS30") }
        }
    }

    @Test
    fun `a TA with no courses is refused rather than told the course is missing`() {
        every { courseRepository.findByTaEmail("ta@sjsu.edu") } returns emptyList()

        mvc.get("/api/admin/canvas/lab?$labQuery") { header("Authorization", "Bearer ta") }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `the admin gets the plan in the shape the CLI parses`() {
        every { canvasSyncService.labPlan("CS30", 2026, "Spring", 1, 1) } returns plan

        mvc.get("/api/admin/canvas/lab?$labQuery") { header("Authorization", "Bearer admin") }.andExpect {
            status { isOk() }
            jsonPath("$.courseCode") { value("CS30") }
            jsonPath("$.labNumber") { value(1) }
            jsonPath("$.startDateTime") { value("2026-02-10T10:00:00") }
            jsonPath("$.endDateTime") { value("2026-02-10T11:15:00") }
            jsonPath("$.problems[0].name") { value("babyshark") }
            jsonPath("$.problems[0].note") { value("Bonus") }
            jsonPath("$.studentEmails[0]") { value("a@sjsu.edu") }
            jsonPath("$.studentGitRepo") { doesNotExist() }
        }
    }

    @Test
    fun `a missing course or lab is a 404 carrying the service's message`() {
        every { canvasSyncService.labPlan("CS30", 2026, "Spring", 1, 1) } throws
            IllegalArgumentException("Lab 1 not found in CS30 section 1. Labs: (none)")

        mvc.get("/api/admin/canvas/lab?$labQuery") { header("Authorization", "Bearer admin") }.andExpect {
            status { isNotFound() }
            jsonPath("$.error") { value("Lab 1 not found in CS30 section 1. Labs: (none)") }
        }
    }

    @Test
    fun `submissions come back as a flat list with the student's email`() {
        every { canvasSyncService.bestSubmissions("CS30", 2026, "Spring", 1, 1, "babyshark") } returns listOf(
            StudentBestSubmission(
                "a@sjsu.edu",
                BestSubmission(7, 10, "submission-2026-07-27T21-39-23.py", "print(1)\n", "2026-07-27T21-39-23"),
            )
        )

        mvc.get("/api/admin/canvas/lab/submissions?$labQuery&problem=babyshark") {
            header("Authorization", "Bearer admin")
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].email") { value("a@sjsu.edu") }
            jsonPath("$[0].submission.highestPassed") { value(7) }
            jsonPath("$[0].submission.total") { value(10) }
            jsonPath("$[0].submission.code") { value("print(1)\n") }
            jsonPath("$[0].submission.submittedAt") { value("2026-07-27T21-39-23") }
        }
    }

    @Test
    fun `submissions are gated the same way as the plan`() {
        taAssignedTo(2)

        mvc.get("/api/admin/canvas/lab/submissions?$labQuery&problem=babyshark") {
            header("Authorization", "Bearer ta")
        }.andExpect {
            status { isForbidden() }
        }
        verify(exactly = 0) { canvasSyncService.bestSubmissions(any(), any(), any(), any(), any(), any()) }

        every { canvasSyncService.bestSubmissions("CS30", 2026, "Spring", 1, 1, "nope") } throws
            IllegalArgumentException("Problem 'nope' is not in lab 1 of CS30 section 1. Problems: babyshark")

        mvc.get("/api/admin/canvas/lab/submissions?$labQuery&problem=nope") {
            header("Authorization", "Bearer admin")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.error") { value("Problem 'nope' is not in lab 1 of CS30 section 1. Problems: babyshark") }
        }
    }

    @Test
    fun `the admin settles a course fragment over every course, in the shape the CLI parses`() {
        every { canvasSyncService.findCourse(CourseQuery("cs3", null, "spr", null), null) } returns
            CourseRef("CS30", 2026, "Spring", 1)

        mvc.get("/api/admin/canvas/course?code=cs3&semester=spr") {
            header("Authorization", "Bearer admin")
        }.andExpect {
            status { isOk() }
            jsonPath("$.code") { value("CS30") }
            jsonPath("$.year") { value(2026) }
            jsonPath("$.semester") { value("Spring") }
            jsonPath("$.section") { value(1) }
        }
    }

    @Test
    fun `a TA settles a course fragment only over their own sections`() {
        every { canvasSyncService.findCourse(CourseQuery("cs30"), "ta@sjsu.edu") } returns
            CourseRef("CS30", 2026, "Spring", 2)

        mvc.get("/api/admin/canvas/course?code=cs30") { header("Authorization", "Bearer ta") }.andExpect {
            status { isOk() }
            jsonPath("$.section") { value(2) }
        }
        verify(exactly = 0) { canvasSyncService.findCourse(any(), isNull()) }
    }

    @Test
    fun `a fragment that fits no course or several is a 404 carrying the listing`() {
        val listing = "multiple cs30 courses match code 'cs30':\n  - CS30 (Section 1, Semester Spring, Year 2026)"
        every { canvasSyncService.findCourse(CourseQuery("cs30"), null) } throws IllegalArgumentException(listing)

        mvc.get("/api/admin/canvas/course?code=cs30") { header("Authorization", "Bearer admin") }.andExpect {
            status { isNotFound() }
            jsonPath("$.error") { value(listing) }
        }
    }

    @Test
    fun `settling a course fragment is gated like the rest`() {
        mvc.get("/api/admin/canvas/course?code=cs30").andExpect {
            status { isUnauthorized() }
        }
        mvc.get("/api/admin/canvas/course?code=cs30") { header("Authorization", "Bearer prof") }.andExpect {
            status { isForbidden() }
            jsonPath("$.error") { value("Only the admin or the section's TA can use this") }
        }
        verify(exactly = 0) { canvasSyncService.findCourse(any(), any()) }
    }
}
