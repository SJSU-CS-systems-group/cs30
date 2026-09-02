package cli

import com.cs30.cli.CanvasAssignment
import com.cs30.cli.CanvasAssignmentGroup
import com.cs30.cli.CanvasClient
import com.cs30.cli.CanvasCourse
import com.cs30.cli.CanvasSubmission
import com.cs30.cli.CanvasSubmissionComment
import com.cs30.cli.CanvasUser
import com.cs30.cli.CliOptions
import com.cs30.cli.Course2Canvas
import com.cs30.cli.Cs30ApiClient
import com.cs30.cli.Cs30ApiException
import com.cs30.cli.Submissions2Canvas
import com.cs30.server.dto.BestSubmission
import com.cs30.server.dto.CanvasLabPlan
import com.cs30.server.dto.CanvasProblemPlan
import com.cs30.server.dto.StudentBestSubmission
import com.cs30.server.dto.StudentOverrideDto
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * The Canvas commands end to end with both sides mocked: the cs30 server they now read from and
 * the Canvas they write to. Now that neither command needs a database, this is where the flow
 * (what is fetched, what is counted, what a server refusal does) is pinned down.
 */
class CanvasMirrorTest {

    private val cs30 = mockk<Cs30ApiClient>()
    private val canvas = mockk<CanvasClient>()
    private val out = mutableListOf<String>()
    private val err = mutableListOf<String>()
    private val cli = mockk<CliOptions>().also {
        every { it.out(capture(out)) } just runs
        every { it.err(capture(err)) } just runs
    }

    private val plan = CanvasLabPlan(
        courseCode = "CS30", section = 1, labNumber = 1,
        startDateTime = LocalDateTime.of(2026, 2, 10, 10, 0),
        endDateTime = LocalDateTime.of(2026, 2, 10, 11, 15),
        problems = listOf(CanvasProblemPlan("babyshark", null), CanvasProblemPlan("tenkinds", "Bonus problems")),
        studentEmails = listOf("amy@sjsu.edu", "bob@sjsu.edu", "cat@sjsu.edu"),
    )

    private fun best(at: String = "2026-07-27T21-39-23") = BestSubmission(7, 10, "submission-$at.py", "print(1)", at)

    private fun mirror() = Submissions2Canvas(cs30, canvas).apply {
        cli = this@CanvasMirrorTest.cli
        code = "CS30"; year = 2026; semester = "Spring"; section = 1; lab = 1
        canvasCourse = "123"
    }

    private fun create() = Course2Canvas(cs30, canvas).apply {
        cli = this@CanvasMirrorTest.cli
        code = "CS30"; year = 2026; semester = "Spring"; section = 1; lab = 1
        canvasCourse = "123"
    }

    @BeforeEach
    fun canvasStubs() {
        every { cs30.studentOverrides() } returns emptyList()
        every { canvas.findCourse("123") } returns CanvasCourse(id = 7, name = "CS30 Spring")
        every { canvas.listStudents(7) } returns listOf(
            CanvasUser(id = 1, name = "Amy", email = "amy@sjsu.edu"),
            // Matched through the login id, which Canvas may report in a different case.
            CanvasUser(id = 2, name = "Bob", loginId = "BOB@sjsu.edu"),
        )
        every { canvas.listAssignments(7) } returns listOf(
            CanvasAssignment(id = 10, name = "lab01"),
            CanvasAssignment(id = 11, name = "LAB01-Bonus"),
        )
        every { canvas.listSubmissions(7, any()) } returns emptyList()
    }

    @Test
    fun `a dry run reads the submissions once per problem and posts nothing`() {
        every { cs30.labPlan("CS30", 2026, "Spring", 1, 1) } returns plan
        every { cs30.bestSubmissions("CS30", 2026, "Spring", 1, 1, "babyshark") } returns listOf(
            StudentBestSubmission("amy@sjsu.edu", best()),
            StudentBestSubmission("bob@sjsu.edu", best()),
            StudentBestSubmission("cat@sjsu.edu", best()),
        )
        every { cs30.bestSubmissions("CS30", 2026, "Spring", 1, 1, "tenkinds") } returns emptyList()

        assertEquals(0, mirror().call())

        verify(exactly = 1) { cs30.bestSubmissions("CS30", 2026, "Spring", 1, 1, "babyshark") }
        verify(exactly = 1) { cs30.bestSubmissions("CS30", 2026, "Spring", 1, 1, "tenkinds") }
        verify(exactly = 0) { canvas.postSubmissionComment(any(), any(), any(), any()) }
        assertTrue(out.contains("  would comment for amy@sjsu.edu (7/10)"), out.toString())
        assertTrue(out.contains("  would comment for bob@sjsu.edu (7/10)"), out.toString())
        assertTrue(err.contains("  WARNING: no Canvas user for cat@sjsu.edu; skipping"), err.toString())
        assertTrue(
            out.contains("Done. would post 2 comment(s), 0 up to date, 3 without a submission, 1 without a Canvas user"),
            "three students have nothing for tenkinds: $out",
        )
    }

    @Test
    fun `a submission already mirrored is up to date, and a real run posts the rest`() {
        every { cs30.labPlan("CS30", 2026, "Spring", 1, 1) } returns plan
        every { cs30.bestSubmissions("CS30", 2026, "Spring", 1, 1, "babyshark") } returns listOf(
            StudentBestSubmission("amy@sjsu.edu", best()),
            StudentBestSubmission("bob@sjsu.edu", best()),
        )
        every { cs30.bestSubmissions("CS30", 2026, "Spring", 1, 1, "tenkinds") } returns emptyList()
        every { canvas.listSubmissions(7, 10) } returns listOf(
            CanvasSubmission(
                id = 1, userId = 1,
                submissionComments = listOf(
                    CanvasSubmissionComment(
                        comment = "Best submission for babyshark: 7/10 test cases passed, submitted 2026-07-27T21-39-23 UTC."
                    )
                ),
            )
        )
        every { canvas.postSubmissionComment(7, 10, 2, any()) } just runs

        assertEquals(0, mirror().apply { applyRequested = true }.call())

        verify(exactly = 1) { canvas.postSubmissionComment(7, 10, 2, match { it.contains("7/10") && it.contains("print(1)") }) }
        // cat has nothing for babyshark, and nobody has anything for tenkinds.
        assertTrue(
            out.contains("Done. posted 1 comment(s), 1 up to date, 4 without a submission, 0 without a Canvas user"),
            out.toString(),
        )
    }

    @Test
    fun `a refusal from the server stops the command before Canvas is touched`() {
        every { cs30.labPlan("CS30", 2026, "Spring", 1, 1) } throws
            Cs30ApiException("the server rejected the CLI token: Valid CLI token required")

        assertEquals(1, mirror().call())
        assertEquals(listOf("ERROR: the server rejected the CLI token: Valid CLI token required"), err)
        verify(exactly = 0) { canvas.findCourse(any()) }

        err.clear()
        assertEquals(1, create().call())
        assertEquals(listOf("ERROR: the server rejected the CLI token: Valid CLI token required"), err)
    }

    @Test
    fun `a server error while reading submissions is reported`() {
        every { cs30.labPlan("CS30", 2026, "Spring", 1, 1) } returns plan
        every { cs30.bestSubmissions("CS30", 2026, "Spring", 1, 1, "babyshark") } throws
            Cs30ApiException("This token is not the TA for CS30 section 1 (Spring 2026)")

        assertEquals(1, mirror().call())
        assertTrue(err.contains("ERROR: This token is not the TA for CS30 section 1 (Spring 2026)"), err.toString())
    }

    @Test
    fun `an overridden email is matched through the student id instead of the email`() {
        // Cat's Canvas account carries a personal address, so only her student id can find her.
        every { cs30.studentOverrides() } returns listOf(StudentOverrideDto("cat@sjsu.edu", "000123456"))
        every { canvas.listStudents(7) } returns listOf(
            CanvasUser(id = 1, name = "Amy", email = "amy@sjsu.edu"),
            CanvasUser(id = 2, name = "Bob", loginId = "BOB@sjsu.edu"),
            CanvasUser(id = 3, name = "Cat", email = "cat.personal@example.com", sisUserId = "000123456"),
        )
        every { cs30.labPlan("CS30", 2026, "Spring", 1, 1) } returns plan
        every { cs30.bestSubmissions("CS30", 2026, "Spring", 1, 1, "babyshark") } returns listOf(
            StudentBestSubmission("cat@sjsu.edu", best()),
        )
        every { cs30.bestSubmissions("CS30", 2026, "Spring", 1, 1, "tenkinds") } returns emptyList()

        assertEquals(0, mirror().call())

        assertTrue(out.contains("Student overrides: 1 mapping(s) from the server"), out.toString())
        assertTrue(out.contains("  would comment for cat@sjsu.edu (7/10)"), out.toString())
        assertEquals(emptyList<String>(), err)
    }

    @Test
    fun `a stale override warns instead of falling back to the email`() {
        // Amy's email would match her Canvas account, but the override stays authoritative.
        every { cs30.studentOverrides() } returns listOf(StudentOverrideDto("amy@sjsu.edu", "000000000"))
        every { cs30.labPlan("CS30", 2026, "Spring", 1, 1) } returns plan
        every { cs30.bestSubmissions("CS30", 2026, "Spring", 1, 1, "babyshark") } returns listOf(
            StudentBestSubmission("amy@sjsu.edu", best()),
        )
        every { cs30.bestSubmissions("CS30", 2026, "Spring", 1, 1, "tenkinds") } returns emptyList()

        assertEquals(0, mirror().call())

        assertTrue(
            err.contains("  WARNING: no Canvas user with student id '000000000' (override for amy@sjsu.edu); skipping"),
            err.toString(),
        )
        assertTrue(
            out.contains("Done. would post 0 comment(s), 0 up to date, 5 without a submission, 1 without a Canvas user"),
            out.toString(),
        )
    }

    @Test
    fun `course2canvas dry run plans from the server's lab and creates nothing`() {
        every { cs30.labPlan("CS30", 2026, "Spring", 1, 1) } returns plan
        every { canvas.findAssignmentGroup(7, "Labs") } returns CanvasAssignmentGroup(id = 3, name = "Labs")
        // Only the plain assignment exists so far; the bonus one is what a real run would create.
        every { canvas.listAssignments(7) } returns listOf(CanvasAssignment(id = 10, name = "lab01"))

        assertEquals(0, create().call())

        assertTrue(out.contains("Lab 1 window: 2026-02-10T10:00:00Z .. 2026-02-10T11:15:00Z"), out.toString())
        assertTrue(out.contains("  exists, skipping LAB01 (id 10); use --force to update"), out.toString())
        assertTrue(out.contains("  would create LAB01-Bonus (points: 100)"), out.toString())
        verify(exactly = 0) { canvas.createAssignment(any(), any()) }
        verify(exactly = 0) { canvas.updateAssignment(any(), any(), any()) }
    }
}
