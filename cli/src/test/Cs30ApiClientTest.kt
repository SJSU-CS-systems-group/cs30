package cli

import com.cs30.cli.Cs30ApiClient
import com.cs30.cli.Cs30ApiException
import com.cs30.cli.Cs30NotFoundException
import com.cs30.server.dto.CourseQuery
import com.cs30.server.dto.CourseRef
import com.fasterxml.jackson.core.JacksonException
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.LocalDateTime

/**
 * The CLI's side of the contract with CanvasSyncController, against a stand-in server: what goes
 * on the wire, how the JSON comes back, and how each failure reads to the person running the
 * command - those messages are all they get, since the server's log is not in front of them.
 */
class Cs30ApiClientTest {

    private lateinit var server: HttpServer
    private var status = 200
    private var body = ""
    private var contentType = "application/json"
    private var receivedAuthorization: String? = null
    private var receivedTarget: String? = null
    private var receivedMethod: String? = null

    // Set to answer per request URI (status to body); otherwise the fixed status/body reply.
    private var route: ((String) -> Pair<Int, String>)? = null

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            receivedAuthorization = exchange.requestHeaders.getFirst("Authorization")
            receivedTarget = exchange.requestURI.toString()
            receivedMethod = exchange.requestMethod
            val (respStatus, respBody) = route?.invoke(exchange.requestURI.toString()) ?: (status to body)
            val bytes = respBody.toByteArray()
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(respStatus, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    private fun baseUrl() = "http://127.0.0.1:${server.address.port}/"

    private fun client(token: String = "secret") = Cs30ApiClient(baseUrl(), token)

    private val planJson = """
        {"courseCode":"CS30","section":1,"labNumber":1,
         "startDateTime":"2026-02-10T10:00:00","endDateTime":"2026-02-10T11:15:00",
         "problems":[{"name":"babyshark","note":"Bonus"},{"name":"tenkinds","note":null}],
         "studentEmails":["a@example.com","b@sjsu.edu"],
         "addedLater":true}
    """.trimIndent()

    @Test
    fun `labPlan sends the token, asks for the lab by query, and parses the plan`() {
        body = planJson

        val plan = client().labPlan("CS 30", 2026, "Spring", 1, 1)

        assertEquals("Bearer secret", receivedAuthorization)
        assertEquals("/api/admin/canvas/lab?code=CS+30&year=2026&semester=Spring&section=1&lab=1", receivedTarget)
        assertEquals("CS30", plan.courseCode)
        assertEquals(1, plan.labNumber)
        assertEquals(LocalDateTime.of(2026, 2, 10, 10, 0), plan.startDateTime)
        assertEquals(LocalDateTime.of(2026, 2, 10, 11, 15), plan.endDateTime)
        assertEquals(listOf("babyshark", "tenkinds"), plan.problems.map { it.name })
        assertEquals("Bonus", plan.problems[0].note)
        assertNull(plan.problems[1].note)
        assertEquals(listOf("a@example.com", "b@sjsu.edu"), plan.studentEmails)
    }

    @Test
    fun `bestSubmissions names the problem and parses the list`() {
        body = """
            [{"email":"a@example.com","submission":{"highestPassed":7,"total":10,
              "fileName":"submission-2026-07-27T21-39-23.py","code":"print(1)\n","submittedAt":"2026-07-27T21-39-23"}}]
        """.trimIndent()

        val submissions = client().bestSubmissions("CS30", 2026, "Spring", 1, 1, "baby shark")

        assertEquals(
            "/api/admin/canvas/lab/submissions?code=CS30&year=2026&semester=Spring&section=1&lab=1&problem=baby+shark",
            receivedTarget,
        )
        assertEquals(1, submissions.size)
        assertEquals("a@example.com", submissions[0].email)
        assertEquals(7, submissions[0].submission.highestPassed)
        assertEquals("print(1)\n", submissions[0].submission.code)
        assertEquals("2026-07-27T21-39-23", submissions[0].submission.submittedAt)

        body = "[]"
        assertTrue(client().bestSubmissions("CS30", 2026, "Spring", 1, 1, "babyshark").isEmpty())
    }

    @Test
    fun `findCourses sends only the filters given and parses the list`() {
        body = """[{"code":"CS30","year":2026,"semester":"Spring","section":1}]"""

        assertEquals(listOf(CourseRef("CS30", 2026, "Spring", 1)), client().findCourses(CourseQuery("cs 3")))
        assertEquals("/api/admin/canvas/courses?code=cs+3", receivedTarget)

        client().findCourses(CourseQuery("cs30", year = 2026, semester = "spr", section = 2))
        assertEquals("/api/admin/canvas/courses?code=cs30&year=2026&semester=spr&section=2", receivedTarget)

        body = "[]"
        assertTrue(client().findCourses(CourseQuery(active = true)).isEmpty())
        assertEquals("/api/admin/canvas/courses?active=true", receivedTarget)
    }

    @Test
    fun `a 404 is a Cs30NotFoundException carrying the server's message`() {
        status = 404
        body = """{"error":"Lab 9 not found in CS30 section 1. Labs: 1, 2"}"""

        val e = assertThrows(Cs30NotFoundException::class.java) {
            client().labPlan("CS30", 2026, "Spring", 1, 9)
        }
        assertEquals("Lab 9 not found in CS30 section 1. Labs: 1, 2", e.message)
    }

    @Test
    fun `finishedLabPlans walks labs until one is missing and keeps only the finished ones`() {
        fun planBody(lab: Int, end: String) =
            """{"courseCode":"CS30","section":1,"labNumber":$lab,
                "startDateTime":"2019-01-01T10:00:00","endDateTime":"$end",
                "problems":[],"studentEmails":[]}"""
        route = { uri ->
            when {
                "lab=1" in uri -> 200 to planBody(1, "2020-01-01T10:00:00")   // finished
                "lab=2" in uri -> 200 to planBody(2, "2999-01-01T10:00:00")   // not finished yet
                "lab=3" in uri -> 200 to planBody(3, "2020-06-01T10:00:00")   // finished
                else -> 404 to """{"error":"Lab 4 not found in CS30 section 1. Labs: 1, 2, 3"}"""
            }
        }

        val plans = client().finishedLabPlans("CS30", 2026, "Spring", 1)

        // Probes 1..4, stops at the missing lab 4, and drops lab 2 as still open.
        assertEquals(listOf(1, 3), plans.map { it.labNumber })
        assertEquals("/api/admin/canvas/lab?code=CS30&year=2026&semester=Spring&section=1&lab=4", receivedTarget)
    }

    @Test
    fun `studentOverrides reads the list from the overrides endpoint`() {
        body = """[{"email":"student@example.com","studentId":"000123456"}]"""

        val overrides = client().studentOverrides()

        assertEquals("GET", receivedMethod)
        assertEquals("/api/admin/canvas/overrides", receivedTarget)
        assertEquals(1, overrides.size)
        assertEquals("student@example.com", overrides[0].email)
        assertEquals("000123456", overrides[0].studentId)

        body = "[]"
        assertTrue(client().studentOverrides().isEmpty())
    }

    @Test
    fun `the override changes go on the query and come back as the server's message`() {
        body = """{"message":"Added override student@example.com -> 000123456"}"""
        assertEquals(
            "Added override student@example.com -> 000123456",
            client().addStudentOverride("student@example.com", "000123456"),
        )
        assertEquals("POST", receivedMethod)
        assertEquals("/api/admin/canvas/overrides?email=student%40example.com&studentId=000123456", receivedTarget)

        body = """{"message":"Removed override student@example.com"}"""
        assertEquals("Removed override student@example.com", client().removeStudentOverride("student@example.com"))
        assertEquals("DELETE", receivedMethod)
        assertEquals("/api/admin/canvas/overrides?email=student%40example.com", receivedTarget)
    }

    @Test
    fun `an HTML answer from an older server throws, never a false success`() {
        // A server without the endpoint answers unknown paths with the web app's page, HTTP 200.
        // The JacksonException reaches the command, which prints NOT_JSON_ERROR for it.
        contentType = "text/html"
        body = "<!DOCTYPE html><html><body>CS30 Code Editor</body></html>"

        assertThrows(JacksonException::class.java) { client().studentOverrides() }
        // The add must not report OK when nothing understood it.
        assertThrows(JacksonException::class.java) { client().addStudentOverride("a@example.com", "1") }
    }

    @Test
    fun `an override change refused for a non-admin token reads in the server's words`() {
        status = 403
        body = """{"error":"Only the admin can change student overrides"}"""

        assertEquals(
            "Only the admin can change student overrides",
            failure { client().addStudentOverride("a@example.com", "1") },
        )
    }

    private fun failure(block: () -> Unit): String =
        assertThrows(Cs30ApiException::class.java) { block() }.message!!

    @Test
    fun `a refused token says so, with the server's reason`() {
        status = 401
        body = """{"error":"Valid CLI token required"}"""

        val message = failure { client("wrong").labPlan("CS30", 2026, "Spring", 1, 1) }

        assertEquals("the server rejected the CLI token: Valid CLI token required", message)
    }

    @Test
    fun `a refusal or a miss is reported in the server's words`() {
        status = 403
        body = """{"error":"This token is not the TA for CS30 section 1 (Spring 2026)"}"""
        assertEquals(
            "This token is not the TA for CS30 section 1 (Spring 2026)",
            failure { client().labPlan("CS30", 2026, "Spring", 1, 1) },
        )

        status = 404
        body = """{"error":"Lab 9 not found in CS30 section 1. Labs: 1, 2"}"""
        assertEquals(
            "Lab 9 not found in CS30 section 1. Labs: 1, 2",
            failure { client().labPlan("CS30", 2026, "Spring", 1, 9) },
        )
    }

    @Test
    fun `an unexpected status shows what was asked and what came back`() {
        status = 502
        contentType = "text/html"
        body = "<html>Bad Gateway</html>"

        val message = failure { client().bestSubmissions("CS30", 2026, "Spring", 1, 1, "babyshark") }

        assertEquals(
            "read submissions for babyshark in lab 1 of CS30 section 1 failed: HTTP 502: <html>Bad Gateway</html>",
            message,
        )
    }

    @Test
    fun `a server that cannot be reached is reported as such`() {
        val url = baseUrl()
        server.stop(0)

        val message = failure { Cs30ApiClient(url, "secret").labPlan("CS30", 2026, "Spring", 1, 1) }

        assertTrue(message.startsWith("cannot reach $url: "), message)
        // The JDK's ConnectException carries no message; the class name stands in rather than "null".
        assertTrue(message.removePrefix("cannot reach $url: ").isNotBlank() && !message.endsWith("null"), message)
    }

    @Test
    fun `missing configuration is reported before any request is made`() {
        assertEquals(
            "no cs30 server configured: pass --server, set CS30_BACKEND_URL, or put cs30.backend.url in cs30.properties",
            failure { Cs30ApiClient("", "secret").labPlan("CS30", 2026, "Spring", 1, 1) },
        )
        assertEquals(
            "no CLI token: pass --token, set CS30_ADMIN_TOKEN, or put cs30.cli.token in cs30.properties",
            failure { client(token = " ").labPlan("CS30", 2026, "Spring", 1, 1) },
        )
        assertNull(receivedTarget, "nothing should have reached the server")
    }
}
