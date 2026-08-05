package com.cs30.judge

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

// Verifies the HTTP contract: each endpoint returns the right status code and
// response shape. The store / readiness / self-test are mocked so we can drive
// every branch (success + each error mapping) deterministically.
@WebMvcTest(JudgeController::class)
class JudgeControllerTest {

    @Autowired lateinit var mvc: MockMvc

    @MockkBean lateinit var store: JudgeStore
    @MockkBean lateinit var readiness: JudgeReadiness
    @MockkBean lateinit var selfTest: JudgeSelfTest

    private val body =
        """{"problem_id":"p","pool_path":"/x","language":"python","source":"print(1)"}"""

    // ---- health / ready / selftest ----

    @Test fun `health is 200 ok`() {
        mvc.get("/health").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("ok") }
        }
    }

    @Test fun `ready is 200 when docker+image present`() {
        every { readiness.check(any()) } returns ReadyStatus(true, "up")
        mvc.get("/ready").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("ready") }
        }
    }

    @Test fun `ready is 503 when not ready`() {
        every { readiness.check(any()) } returns ReadyStatus(false, "docker down")
        mvc.get("/ready").andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.status") { value("not_ready") }
        }
    }

    @Test fun `selftest is 200 when it grades AC`() {
        every { selfTest.run() } returns SelfTestResult(true, "AC", 1, 1, "ok")
        mvc.get("/selftest").andExpect {
            status { isOk() }
            jsonPath("$.ok") { value(true) }
            jsonPath("$.verdict") { value("AC") }
        }
    }

    @Test fun `selftest is 503 when it fails`() {
        every { selfTest.run() } returns SelfTestResult(false, "ERROR", 0, 0, "boom")
        mvc.get("/selftest").andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.ok") { value(false) }
        }
    }

    // ---- /submit: success shape + every error mapping ----

    @Test fun `submit is 200 with the verdict body`() {
        every { store.submitSync(any()) } returns
            SubmitResult("AC", 2, 2, 0.5, emptyList(), null)
        mvc.post("/submit") { contentType = MediaType.APPLICATION_JSON; content = body }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("AC") }
            jsonPath("$.passed") { value(2) }
            jsonPath("$.total") { value(2) }
            jsonPath("$.max_time_s") { value(0.5) }
        }
    }

    @Test fun `submit maps JudgeError to 400`() {
        every { store.submitSync(any()) } throws JudgeError("bad request")
        mvc.post("/submit") { contentType = MediaType.APPLICATION_JSON; content = body }
            .andExpect { status { isBadRequest() } }
    }

    @Test fun `submit maps QueueFull to 429`() {
        every { store.submitSync(any()) } throws QueueFull("at capacity")
        mvc.post("/submit") { contentType = MediaType.APPLICATION_JSON; content = body }
            .andExpect { status { isTooManyRequests() } }
    }

    @Test fun `submit maps SyncTimeout to 504`() {
        every { store.submitSync(any()) } throws SyncTimeout("overloaded")
        mvc.post("/submit") { contentType = MediaType.APPLICATION_JSON; content = body }
            .andExpect { status { isGatewayTimeout() } }
    }

    // No domain-specific handler matches, but the failure must still be diagnosable. Without the
    // catch-all this produced Spring's whitelabel body, which has no `detail` — so the backend logged an
    // empty envelope and the cause survived only in this service's own journal.
    @Test fun `unexpected errors are 500 and carry the cause in detail`() {
        every { store.submitSync(any()) } throws RuntimeException("boom")
        mvc.post("/submit") { contentType = MediaType.APPLICATION_JSON; content = body }
            .andExpect {
                status { isInternalServerError() }
                jsonPath("$.detail") { value("boom") }
            }
    }

    // The catch-all above must not be greedy: Spring's own MVC exceptions keep their own statuses.
    // A malformed body is the caller's mistake (400), not a judge failure (500).
    @Test fun `a malformed request body stays a 400 and is not swallowed by the catch-all`() {
        mvc.post("/submit") { contentType = MediaType.APPLICATION_JSON; content = "{not json" }
            .andExpect { status { isBadRequest() } }
    }

    @Test fun `submit with missing required field is 400`() {
        val bad = """{"problem_id":"p","language":"python","source":"print(1)"}"""  // no pool_path
        mvc.post("/submit") { contentType = MediaType.APPLICATION_JSON; content = bad }
            .andExpect { status { isBadRequest() } }
    }

    // ---- /run: success shape + shared error mapping ----

    @Test fun `run is 200 with testcases`() {
        every { store.runSync(any()) } returns
            RunResult(listOf(RunCase("custom/1", "AC", 0.1, "in", "exp", "out", "")), null)
        mvc.post("/run") { contentType = MediaType.APPLICATION_JSON; content = body }.andExpect {
            status { isOk() }
            jsonPath("$.testcases[0].name") { value("custom/1") }
            jsonPath("$.testcases[0].status") { value("AC") }
        }
    }

    @Test fun `run maps JudgeError to 400`() {
        every { store.runSync(any()) } throws JudgeError("too many custom cases")
        mvc.post("/run") { contentType = MediaType.APPLICATION_JSON; content = body }
            .andExpect { status { isBadRequest() } }
    }

    // ---- grading response mapping: run (no custom / custom) + submit ----

    @Test fun `run without custom cases returns sample verdicts and forwards no custom stdins`() {
        val req = slot<RunRequest>()
        every { store.runSync(capture(req)) } returns RunResult(
            listOf(
                RunCase("sample/1", "AC", 0.5, "2 3", "5", "5", ""),
                RunCase("sample/2", "WA", 0.5, "4 5", "9", "8", ""),
            ),
            null,
        )
        mvc.post("/run") { contentType = MediaType.APPLICATION_JSON; content = body }.andExpect {
            status { isOk() }
            jsonPath("$.testcases.length()") { value(2) }
            jsonPath("$.testcases[0].name") { value("sample/1") }
            jsonPath("$.testcases[0].status") { value("AC") }
            jsonPath("$.testcases[0].expected") { value("5") }
            jsonPath("$.testcases[1].status") { value("WA") }
        }
        assertTrue(req.captured.customStdins.isEmpty())
    }

    @Test fun `run with custom cases forwards them and returns their verdicts`() {
        val req = slot<RunRequest>()
        every { store.runSync(capture(req)) } returns RunResult(
            listOf(
                RunCase("sample/1", "AC", 0.5, "2 3", "5", "5", ""),
                RunCase("custom/1", null, null, "5 6", null, "11", ""),   // custom: ungraded
                RunCase("custom/2", null, null, "7 8", null, "15", ""),
            ),
            null,
        )
        val withCustom =
            """{"problem_id":"p","pool_path":"/x","language":"python","source":"print(1)","custom_stdins":["5 6","7 8"]}"""
        mvc.post("/run") { contentType = MediaType.APPLICATION_JSON; content = withCustom }.andExpect {
            status { isOk() }
            jsonPath("$.testcases.length()") { value(3) }
            jsonPath("$.testcases[1].name") { value("custom/1") }
            jsonPath("$.testcases[1].stdout") { value("11") }
            jsonPath("$.testcases[2].name") { value("custom/2") }
        }
        assertEquals(listOf("5 6", "7 8"), req.captured.customStdins)
    }

    @Test fun `submit returns the full graded response`() {
        every { store.submitSync(any()) } returns SubmitResult(
            status = "WA", passed = 1, total = 3, maxTimeS = 0.5,
            cases = listOf(
                SubmitCase("sample/1", "AC", 0.5, "2 3", "5", "5", ""),      // public: full detail
                SubmitCase("secret/1", "WA", 0.5, null, null, null, null),   // secret: status + time only
                SubmitCase("secret/2", "TLE", 1.0, null, null, null, null),
            ),
            compileOutput = null,
        )
        mvc.post("/submit") { contentType = MediaType.APPLICATION_JSON; content = body }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("WA") }
            jsonPath("$.passed") { value(1) }
            jsonPath("$.total") { value(3) }
            jsonPath("$.max_time_s") { value(0.5) }
            jsonPath("$.testcases.length()") { value(3) }
            jsonPath("$.testcases[0].name") { value("sample/1") }
            jsonPath("$.testcases[0].input") { value("2 3") }
            jsonPath("$.testcases[0].expected") { value("5") }
            jsonPath("$.testcases[1].name") { value("secret/1") }
            jsonPath("$.testcases[1].status") { value("WA") }
            jsonPath("$.testcases[2].status") { value("TLE") }
        }
    }

    @Test fun `submit CE returns compile output and no testcases`() {
        every { store.submitSync(any()) } returns
            SubmitResult("CE", 0, 0, 0.0, emptyList(), "Main.java:1: error: ';' expected")
        mvc.post("/submit") { contentType = MediaType.APPLICATION_JSON; content = body }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("CE") }
            jsonPath("$.compile_output") { value("Main.java:1: error: ';' expected") }
            jsonPath("$.testcases.length()") { value(0) }
        }
    }

    @Test fun `submit forwards the parsed request to the store`() {
        val req = slot<SubmitRequest>()
        every { store.submitSync(capture(req)) } returns SubmitResult("AC", 1, 1, 0.5, emptyList(), null)
        mvc.post("/submit") { contentType = MediaType.APPLICATION_JSON; content = body }
            .andExpect { status { isOk() } }
        assertEquals("p", req.captured.problemId)
        assertEquals("/x", req.captured.poolPath)
        assertEquals("python", req.captured.language)
        assertEquals("print(1)", req.captured.source)
    }
}
