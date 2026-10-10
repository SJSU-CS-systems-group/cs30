package com.cs30.server.controller

import com.cs30.server.models.Course
import com.cs30.server.models.Problem
import com.cs30.server.models.ScheduledLab
import com.cs30.server.repository.LoginSessionRepository
import com.cs30.server.service.ApiTokenStore
import com.cs30.server.service.AppTimeZoneService
import com.cs30.server.service.CourseService
import com.cs30.server.service.CourseYamlService
import com.cs30.server.service.CourseYamlSyncSettings
import com.cs30.server.service.GitService
import com.cs30.server.service.LabHealthService
import com.cs30.server.service.ProblemService
import com.cs30.server.service.TaAccess
import com.cs30.server.service.TaDashboardSettings
import com.cs30.server.service.TaIdentityService
import data.TaProblemDetail
import data.TaTestCase
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

/**
 * `GET /api/ta/labs/{labId}/problems/{slug}` — the only endpoint that serves hidden (secret) test
 * data, so these cover who can reach it: only a TA of the lab's own course, and only for a problem
 * that is actually in that lab. Anything else must never touch the problem pool.
 */
@WebMvcTest
@ContextConfiguration(classes = [TaController::class, TaControllerTest.Mocks::class])
@AutoConfigureMockMvc(addFilters = false)
class TaControllerTest {

    @TestConfiguration
    class Mocks {
        @Bean fun taIdentityService(): TaIdentityService = mockk()
        @Bean fun problemService(): ProblemService = mockk()
        @Bean fun loginSessionRepository(): LoginSessionRepository = mockk(relaxed = true)
        @Bean fun apiTokenStore(): ApiTokenStore = mockk(relaxed = true)
        @Bean fun gitService(): GitService = mockk(relaxed = true)
        @Bean fun labHealthService(): LabHealthService = mockk(relaxed = true)
        @Bean fun appTimeZoneService(): AppTimeZoneService = mockk(relaxed = true)
        @Bean fun courseService(): CourseService = mockk(relaxed = true)
        @Bean fun courseYamlService(): CourseYamlService = mockk(relaxed = true)
        @Bean fun courseYamlSyncSettings(): CourseYamlSyncSettings = mockk(relaxed = true)
        @Bean fun taDashboardSettings() = TaDashboardSettings(largeProblemBytes = 5_000_000)
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var taIdentityService: TaIdentityService
    @Autowired lateinit var problemService: ProblemService

    private val ownCourse = Course(
        id = "course-1", code = "CS30", section = 1, year = 2026, semester = "Fall",
        language = "python", problemGitRepo = "/pool", taEmails = mutableSetOf("ta@sjsu.edu"),
    ).apply {
        addLab(ScheduledLab(id = "lab-own", labNumber = 1).apply {
            addProblem(Problem(name = "hello-world", language = "python"))
        })
    }

    private val otherCourse = Course(
        id = "course-2", code = "CS30", section = 2, year = 2026, semester = "Fall",
        language = "python", problemGitRepo = "/pool",
    ).apply {
        addLab(ScheduledLab(id = "lab-other", labNumber = 1).apply {
            addProblem(Problem(name = "hello-world", language = "python"))
        })
    }

    private val detail = TaProblemDetail(
        slug = "hello-world", html = "<h1>Hello</h1>", css = "",
        testCases = listOf(TaTestCase(name = "secret/1", hidden = true, input = "in", expected = "out"))
    )

    @BeforeEach
    fun reset() {
        clearMocks(taIdentityService, problemService)
        every { taIdentityService.authorize(null) } returns TaAccess.Unauthenticated
        every { taIdentityService.authorize("Bearer student") } returns TaAccess.NotATa
        every { taIdentityService.authorize("Bearer ta") } returns TaAccess.Granted("ta@sjsu.edu", listOf(ownCourse))
        every { problemService.getProblemDetailForTa(ownCourse, "hello-world") } returns detail
    }

    @Test
    fun `no session gets 401 and no problem data is read`() {
        mvc.get("/api/ta/labs/lab-own/problems/hello-world").andExpect { status { isUnauthorized() } }
        verify(exactly = 0) { problemService.getProblemDetailForTa(any(), any()) }
    }

    @Test
    fun `a non-TA session gets 403 and no problem data is read`() {
        mvc.get("/api/ta/labs/lab-own/problems/hello-world") { header("Authorization", "Bearer student") }
            .andExpect { status { isForbidden() } }
        verify(exactly = 0) { problemService.getProblemDetailForTa(any(), any()) }
    }

    @Test
    fun `a lab of another course gets 404`() {
        mvc.get("/api/ta/labs/lab-other/problems/hello-world") { header("Authorization", "Bearer ta") }
            .andExpect { status { isNotFound() } }
        verify(exactly = 0) { problemService.getProblemDetailForTa(any(), any()) }
    }

    @Test
    fun `a slug that is not one of the lab's problems gets 404`() {
        mvc.get("/api/ta/labs/lab-own/problems/other-problem") { header("Authorization", "Bearer ta") }
            .andExpect { status { isNotFound() } }
        verify(exactly = 0) { problemService.getProblemDetailForTa(any(), any()) }
    }

    @Test
    fun `the course TA gets description and hidden tests with no-store caching`() {
        mvc.get("/api/ta/labs/lab-own/problems/hello-world") { header("Authorization", "Bearer ta") }
            .andExpect {
                status { isOk() }
                header { string("Cache-Control", "no-store") }
                jsonPath("$.slug") { value("hello-world") }
                jsonPath("$.testCases[0].name") { value("secret/1") }
                jsonPath("$.testCases[0].hidden") { value(true) }
                jsonPath("$.testCases[0].input") { value("in") }
                jsonPath("$.testCases[0].expected") { value("out") }
            }
    }

    @Test
    fun `an unreadable problem gets 500 instead of an unhandled exception`() {
        every { problemService.getProblemDetailForTa(ownCourse, "hello-world") } throws java.io.IOException("Permission denied")

        mvc.get("/api/ta/labs/lab-own/problems/hello-world") { header("Authorization", "Bearer ta") }
            .andExpect { status { isInternalServerError() } }
    }
}
