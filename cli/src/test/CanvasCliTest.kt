package cli

import com.cs30.cli.CanvasAssignment
import com.cs30.cli.CanvasAssignmentGroup
import com.cs30.cli.CanvasClient
import com.cs30.cli.CanvasCourse
import com.cs30.cli.CanvasException
import com.cs30.cli.CanvasSubmission
import com.cs30.cli.CanvasSubmissionComment
import com.cs30.cli.CanvasTerm
import com.cs30.cli.CanvasUser
import com.cs30.cli.CliOptions
import com.cs30.cli.Course2Canvas
import com.cs30.cli.NOT_JSON_ERROR
import com.cs30.cli.Cs30ApiClient
import com.cs30.cli.Cs30ApiException
import com.cs30.cli.Submissions2Canvas
import com.cs30.cli.assignmentNameCollisions
import com.cs30.cli.canvasAssignmentName
import com.cs30.cli.normalizeAssignmentName
import com.cs30.cli.selectCanvasCourse
import com.cs30.server.dto.BestSubmission
import com.cs30.server.dto.CanvasLabPlan
import com.cs30.server.dto.CanvasProblemPlan
import com.cs30.server.dto.CourseQuery
import com.cs30.server.dto.CourseRef
import com.cs30.server.dto.StudentBestSubmission
import com.cs30.server.dto.StudentOverrideDto
import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import picocli.CommandLine
import picocli.CommandLine.MissingParameterException
import java.time.LocalDateTime

/**
 * The Canvas commands, course2canvas and submissions2canvas, in one place:
 *
 * - option wiring: building a CommandLine is what validates option names, so a duplicate or
 *   malformed name fails here instead of at CLI startup, where it would break every subcommand;
 * - assignment naming: assignments are often created in Canvas by hand, so the derived name has to
 *   match that convention exactly, or a name off by a word or by case silently syncs nothing;
 * - dates and points: Canvas rejects a datetime without seconds, and every assignment is worth 100;
 * - the comment body and the timestamp that decides whether a submission was already mirrored;
 *   getting that wrong either re-posts on every run or never posts at all;
 * - picking a Canvas course from a name/code fragment, and what the listings say when that fails;
 * - the whole flow with both sides mocked, the cs30 server they read from and the Canvas they write
 *   to: what is fetched, what is counted, what a server refusal does.
 *
 * Commands are built from instances with mocked clients: main() supplies real ones at runtime, and
 * parsing arguments never touches them.
 */
class CanvasCliTest {

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

    private fun submission(
        passed: Int = 7,
        total: Int = 10,
        code: String = "print(1)",
        at: String = "2026-07-27T21-39-23",
        fileName: String = "submission-$at.py",
    ) = BestSubmission(passed, total, fileName, code, at)

    private fun mirror() = Submissions2Canvas(cs30, canvas).apply {
        cli = this@CanvasCliTest.cli
        code = "CS30"; year = 2026; semester = "Spring"; section = 1; lab = 1
        canvasCourse = "123"
    }

    private fun create() = Course2Canvas(cs30, canvas).apply {
        cli = this@CanvasCliTest.cli
        code = "CS30"; year = 2026; semester = "Spring"; section = 1; lab = 1
        canvasCourse = "123"
    }

    /** The fully spelled-out course fits itself alone; the fragment tests stub their own. */
    @BeforeEach
    fun cs30Stubs() {
        every { cs30.findCourses(CourseQuery("CS30", 2026, "Spring", 1)) } returns
            listOf(CourseRef("CS30", 2026, "Spring", 1))
        every { cs30.studentOverrides() } returns emptyList()
    }

    @BeforeEach
    fun canvasStubs() {
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

    // ---------------------------------------------------------------- option wiring (picocli)

    private fun spec() = CommandLine(Course2Canvas(mockk(relaxed = true), mockk(relaxed = true)))

    private fun mirrorSpec() = CommandLine(Submissions2Canvas(mockk(relaxed = true), mockk(relaxed = true)))

    private val required = arrayOf(
        "--cs30-course-code", "CS30", "--cs30-year", "2026", "--cs30-semester", "Fall",
        "--cs30-section", "1", "--cs30-lab", "1", "--canvas-course", "123",
    )

    @Test
    fun `course2canvas option names are valid and unique`() {
        val cmd = spec()
        assertEquals("course2canvas", cmd.commandName)
    }

    @Test
    fun `a bare run is a dry run`() {
        val cmd = spec()
        val parsed = cmd.parseArgs(*required)
        assertTrue(parsed.hasMatchedOption("--cs30-course-code"))
        assertTrue(
            cmd.getCommand<Course2Canvas>().dryrun,
            "with no flags the command must not change Canvas",
        )
    }

    @Test
    fun `--no-dryrun turns off the dry run`() {
        val cmd = spec()
        cmd.parseArgs(*required, "--no-dryrun")
        assertEquals(false, cmd.getCommand<Course2Canvas>().dryrun)
    }

    @Test
    fun `--dryrun keeps the dry run on`() {
        val cmd = spec()
        cmd.parseArgs(*required, "--dryrun")
        assertTrue(cmd.getCommand<Course2Canvas>().dryrun)
    }

    @Test
    fun `force defaults to false and can be negated`() {
        val cmd = spec()
        cmd.parseArgs(
            "--cs30-course-code", "CS30", "--cs30-year", "2026", "--cs30-semester", "Fall",
            "--cs30-section", "1", "--cs30-lab", "1", "--canvas-course", "123", "--force",
        )
        assertEquals(true, cmd.getCommand<Course2Canvas>().force)
    }

    @Test
    fun `submissions2canvas is a bare dry run by default`() {
        val cmd = mirrorSpec()
        assertEquals("submissions2canvas", cmd.commandName)
        cmd.parseArgs(*required)
        val command = cmd.getCommand<Submissions2Canvas>()
        assertTrue(command.dryrun, "with no flags the command must not post to Canvas")
        assertEquals(false, command.forceComment)
    }

    @Test
    fun `submissions2canvas accepts --no-dryrun and --force-comment`() {
        val cmd = mirrorSpec()
        cmd.parseArgs(*required, "--no-dryrun", "--force-comment")
        val command = cmd.getCommand<Submissions2Canvas>()
        assertEquals(false, command.dryrun)
        assertEquals(true, command.forceComment)
    }

    @Test
    fun `optional canvas section rubric and group are parsed`() {
        val cmd = spec()
        cmd.parseArgs(
            "--cs30-course-code", "CS30", "--cs30-year", "2026", "--cs30-semester", "Fall",
            "--cs30-section", "2", "--cs30-lab", "3", "--canvas-course", "practice",
            "--canvas-section", "Section 2", "--rubric", "Lab Rubric",
            "--assignment-group", "labs",
        )
        val command = cmd.getCommand<Course2Canvas>()
        assertEquals("practice", command.canvasCourse)
        assertEquals("Section 2", command.canvasSection)
        assertEquals("Lab Rubric", command.rubric)
        assertEquals("labs", command.assignmentGroup)
        assertEquals(3, command.lab)
    }

    @Test
    fun `submissions2canvas needs only a code fragment and a lab to name the cs30 course`() {
        val cmd = mirrorSpec()
        cmd.parseArgs("--cs30-course-code", "cs3", "--cs30-lab", "2", "--canvas-course", "practice")
        val command = cmd.getCommand<Submissions2Canvas>()
        assertEquals("cs3", command.code)
        assertNull(command.year, "an omitted year must not filter on 0")
        assertNull(command.semester)
        assertNull(command.section, "an omitted section must not filter on 0")
        assertEquals(2, command.lab)
        assertEquals("practice", command.canvasCourse)
    }

    @Test
    fun `submissions2canvas narrowing options are parsed when given`() {
        val cmd = mirrorSpec()
        cmd.parseArgs(
            "--cs30-course-code", "cs30", "--cs30-year", "2026", "--cs30-semester", "fa",
            "--cs30-section", "2", "--cs30-lab", "1", "--canvas-course", "123",
        )
        val command = cmd.getCommand<Submissions2Canvas>()
        assertEquals(2026, command.year)
        assertEquals("fa", command.semester)
        assertEquals(2, command.section)
    }

    @Test
    fun `submissions2canvas still requires the code, lab and canvas course`() {
        assertThrows(MissingParameterException::class.java) {
            mirrorSpec().parseArgs("--cs30-lab", "1", "--canvas-course", "123")
        }
    }

    @Test
    fun `course2canvas still spells out the whole cs30 course`() {
        assertThrows(MissingParameterException::class.java) {
            spec().parseArgs("--cs30-course-code", "CS30", "--cs30-lab", "1", "--canvas-course", "123")
        }
    }

    // ---------------------------------------------------------------- assignment names

    @Test
    fun `no note gives a bare lab name`() {
        assertEquals("LAB00", canvasAssignmentName(0, null))
        assertEquals("LAB01", canvasAssignmentName(1, ""))
        assertEquals("LAB12", canvasAssignmentName(12, "   "))
    }

    @Test
    fun `lab numbers are padded to two digits`() {
        assertEquals("LAB01", canvasAssignmentName(1, null))
        assertEquals("LAB09", canvasAssignmentName(9, null))
        assertEquals("LAB10", canvasAssignmentName(10, null))
        assertEquals("LAB01-Bonus", canvasAssignmentName(1, "Bonus round"))
    }

    @Test
    fun `the first word of the note becomes the suffix`() {
        assertEquals("LAB00-Bonus", canvasAssignmentName(0, "Bonus"))
        assertEquals("LAB00-Bonus", canvasAssignmentName(0, "Bonus problems for extra credit"))
        assertEquals("LAB03-Extra", canvasAssignmentName(3, "  Extra credit  "))
    }

    @Test
    fun `punctuation around the first word is dropped`() {
        assertEquals("LAB00-Bonus", canvasAssignmentName(0, "Bonus: harder version"))
        assertEquals("LAB00-Bonus", canvasAssignmentName(0, "(Bonus) optional"))
        assertEquals("LAB00", canvasAssignmentName(0, "!!!"), "a note with no word characters adds nothing")
    }

    @Test
    fun `names compare case-insensitively so hand-typed assignments still match`() {
        assertEquals(normalizeAssignmentName("LAB01-Bonus"), normalizeAssignmentName("lab01-bonus"))
        assertEquals(normalizeAssignmentName("LAB01"), normalizeAssignmentName("  Lab01 "))
    }

    @Test
    fun `two problems without notes collide`() {
        val collisions = assignmentNameCollisions(
            0,
            listOf(CanvasProblemPlan("babyshark", null), CanvasProblemPlan("pascalmagic", null)),
        )
        assertEquals(1, collisions.size)
        assertEquals(listOf("babyshark", "pascalmagic"), collisions["LAB00"])
    }

    @Test
    fun `notes sharing a first word collide`() {
        val collisions = assignmentNameCollisions(
            2,
            listOf(CanvasProblemPlan("a", "Bonus one"), CanvasProblemPlan("b", "Bonus two")),
        )
        assertEquals(listOf("a", "b"), collisions["LAB02-Bonus"])
    }

    @Test
    fun `distinct notes do not collide`() {
        val collisions = assignmentNameCollisions(
            1,
            listOf(
                CanvasProblemPlan("main", null),
                CanvasProblemPlan("extra", "Bonus"),
                CanvasProblemPlan("third", "Challenge round"),
            ),
        )
        assertTrue(collisions.isEmpty(), "expected no collisions, got $collisions")
    }

    // ---------------------------------------------------------------- dates and points

    @Test
    fun `whole-minute times keep their seconds`() {
        assertEquals("2026-02-10T10:00:00Z", create().isoUtc(LocalDateTime.of(2026, 2, 10, 10, 0)))
    }

    @Test
    fun `times with seconds are unchanged`() {
        assertEquals("2026-02-10T11:15:30Z", create().isoUtc(LocalDateTime.of(2026, 2, 10, 11, 15, 30)))
    }

    @Test
    fun `every emitted timestamp carries a seconds component`() {
        // OffsetDateTime.toString() omits zero seconds, the common case for lab times on a whole minute.
        val command = create()
        listOf(
            LocalDateTime.of(2026, 1, 1, 0, 0),
            LocalDateTime.of(2026, 12, 31, 23, 59),
            LocalDateTime.of(2026, 6, 15, 9, 5, 0),
        ).forEach {
            val formatted = command.isoUtc(it)
            assertTrue(
                Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$""").matches(formatted),
                "Canvas needs full ISO 8601 with seconds, got $formatted",
            )
        }
    }

    @Test
    fun `points possible is a fixed 100`() {
        assertEquals(100, Course2Canvas.POINTS_POSSIBLE)
    }

    // ---------------------------------------------------------------- the comment and its timestamp

    private fun withComments(vararg texts: String) =
        CanvasSubmission(
            id = 1,
            userId = 5,
            submissionComments = texts.map { CanvasSubmissionComment(comment = it) },
        )

    @Test
    fun `no comments means nothing was mirrored yet`() {
        assertNull(mirror().lastSyncedTimestamp(CanvasSubmission(id = 1, userId = 5)))
        assertNull(mirror().lastSyncedTimestamp(withComments()))
    }

    @Test
    fun `unrelated comments are ignored`() {
        val existing = withComments("Nice work!", "see me after class")
        assertNull(mirror().lastSyncedTimestamp(existing))
    }

    @Test
    fun `the newest mirrored timestamp wins`() {
        val existing = withComments(
            "Best submission for p: 3/10 test cases passed, submitted 2026-07-20T10-00-00 UTC.",
            "a human comment",
            "Best submission for p: 7/10 test cases passed, submitted 2026-07-27T21-39-23 UTC.",
        )
        assertEquals("2026-07-27T21-39-23", mirror().lastSyncedTimestamp(existing))
    }

    @Test
    fun `the timestamp round-trips out of the comment this tool writes`() {
        val command = mirror()
        val text = command.commentFor("babyshark", submission(at = "2026-07-27T21-39-23"))
        val parsed = command.lastSyncedTimestamp(withComments(text))
        assertEquals(
            "2026-07-27T21-39-23", parsed,
            "the timestamp in a comment must be readable back, or re-runs post duplicates",
        )
    }

    @Test
    fun `comment states the score and inlines escaped source`() {
        val text = mirror().commentFor("babyshark", submission(passed = 7, total = 10, code = "if (a<b) {}"))
        assertTrue(text.contains("7/10"), text)
        assertFalse(text.contains("%"), "the raw counts are reported, not a percentage: $text")
        assertTrue(text.contains("babyshark"), text)
        assertTrue(text.contains("<pre>"), text)
        assertTrue(text.contains("if (a&lt;b) {}"), "source must be HTML escaped: $text")
        assertFalse(text.contains("if (a<b)"), "raw unescaped source must not appear")
    }

    @Test
    fun `oversized source is omitted rather than inlined`() {
        val big = "x".repeat(9 * 1024)
        val text = mirror().commentFor("babyshark", submission(code = big))
        assertFalse(text.contains("<pre>"), "source over the limit must not be inlined")
        assertTrue(text.contains("Source omitted"), text)
        assertTrue(text.contains("7/10"), "the score is still reported: $text")
    }

    @Test
    fun `a zero-testcase submission is reported as is`() {
        val text = mirror().commentFor("babyshark", submission(passed = 0, total = 0))
        assertTrue(text.contains("0/0"), text)
    }

    // ---------------------------------------------------------------- picking a Canvas course

    private val fall26 = CanvasCourse(
        id = 1, name = "CS 30 Fall 2026", courseCode = "CS30-F26",
        workflowState = "available", concluded = false, term = CanvasTerm(10, "Fall 2026"),
    )
    private val fall25 = CanvasCourse(
        id = 2, name = "CS 30 Fall 2025", courseCode = "CS30-F25",
        workflowState = "available", concluded = true, term = CanvasTerm(9, "Fall 2025"),
    )
    private val lab = CanvasCourse(id = 3, name = "CS 30 Lab", courseCode = "CS30L", workflowState = "unpublished")
    private val sandbox = CanvasCourse(id = 4, name = "Sandbox", courseCode = "SBX", workflowState = "completed")
    private val courses = listOf(fall26, fall25, lab, sandbox)

    private fun noCourse(courses: List<CanvasCourse>, query: String): String =
        assertThrows(CanvasException::class.java) { selectCanvasCourse(courses, query) }.message!!

    @Test
    fun `an exact name wins over a course whose name contains it`() {
        val plain = CanvasCourse(id = 5, name = "CS30")
        val longer = CanvasCourse(id = 6, name = "CS30 Lab")

        assertEquals(plain, selectCanvasCourse(listOf(longer, plain), "cs30"))
        assertEquals(longer, selectCanvasCourse(listOf(longer, plain), "cs30 lab"))
    }

    @Test
    fun `an exact course code wins the same way`() {
        assertEquals(fall26, selectCanvasCourse(courses, "cs30-f26"))
    }

    @Test
    fun `a unique substring of the name or code resolves, ignoring case`() {
        assertEquals(fall26, selectCanvasCourse(courses, "F26"))
        assertEquals(sandbox, selectCanvasCourse(courses, "sand"))
    }

    @Test
    fun `several matches are an error listing each with its term and state`() {
        val message = noCourse(courses, "cs 30")

        assertTrue(message.startsWith("multiple Canvas courses match 'cs 30':"), message)
        assertTrue(message.contains("\n  - 1: CS 30 Fall 2026 (CS30-F26, Fall 2026)"), message)
        assertTrue(message.contains("\n  - 2: CS 30 Fall 2025 (CS30-F25, Fall 2025, concluded)"), message)
        assertTrue(message.contains("\n  - 3: CS 30 Lab (CS30L, unpublished)"), message)
        assertFalse(message.contains("Sandbox"), message)
        assertTrue(message.endsWith("Pass the course id or a longer fragment."), message)
    }

    @Test
    fun `two courses with the same exact name are ambiguous, not first wins`() {
        val a = CanvasCourse(id = 1, name = "CS30", term = CanvasTerm(1, "Fall 2025"))
        val b = CanvasCourse(id = 2, name = "CS30", term = CanvasTerm(2, "Fall 2026"))

        val message = noCourse(listOf(a, b), "CS30")

        assertTrue(message.contains("1: CS30 (Fall 2025)"), message)
        assertTrue(message.contains("2: CS30 (Fall 2026)"), message)
    }

    @Test
    fun `no match lists only the active courses`() {
        val message = noCourse(courses, "math")

        assertTrue(message.startsWith("no Canvas course matching 'math'. Active courses:"), message)
        assertTrue(message.contains("\n  - 1: CS 30 Fall 2026 (CS30-F26, Fall 2026)"), message)
        assertTrue(message.contains("\n  - 3: CS 30 Lab (CS30L, unpublished)"), message)
        assertFalse(message.contains("Fall 2025"), message)
        assertFalse(message.contains("Sandbox"), message)
    }

    @Test
    fun `no match with nothing active says so`() {
        assertTrue(noCourse(listOf(fall25, sandbox), "math").endsWith("Active courses: (none)"))
    }

    @Test
    fun `active means not concluded, not completed and not deleted`() {
        assertTrue(fall26.active)
        assertTrue(lab.active, "an unpublished course is being set up, so it counts")
        assertTrue(CanvasCourse(id = 7, name = "bare").active, "no state information means no reason to hide it")
        assertFalse(fall25.active, "concluded by its term")
        assertFalse(sandbox.active, "concluded by hand")
        assertFalse(CanvasCourse(id = 8, name = "gone", workflowState = "deleted").active)
    }

    @Test
    fun `describe omits a course code that only repeats the name`() {
        assertEquals("9: CS30", CanvasCourse(id = 9, name = "CS30", courseCode = "cs30").describe())
        assertEquals("9: CS30 (CS30-01)", CanvasCourse(id = 9, name = "CS30", courseCode = "CS30-01").describe())
    }

    // ---------------------------------------------------------------- the whole flow, both sides mocked

    @Test
    fun `a dry run reads the submissions once per problem and posts nothing`() {
        every { cs30.labPlan("CS30", 2026, "Spring", 1, 1) } returns plan
        every { cs30.bestSubmissions("CS30", 2026, "Spring", 1, 1, "babyshark") } returns listOf(
            StudentBestSubmission("amy@sjsu.edu", submission()),
            StudentBestSubmission("bob@sjsu.edu", submission()),
            StudentBestSubmission("cat@sjsu.edu", submission()),
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
            StudentBestSubmission("amy@sjsu.edu", submission()),
            StudentBestSubmission("bob@sjsu.edu", submission()),
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
            StudentBestSubmission("cat@sjsu.edu", submission()),
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
            StudentBestSubmission("amy@sjsu.edu", submission()),
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
    fun `an older server without the overrides endpoint reads as one clear error`() {
        every { cs30.labPlan("CS30", 2026, "Spring", 1, 1) } returns plan
        // What the client's mapper actually throws on the web app's HTML.
        val notJson = assertThrows(JacksonException::class.java) {
            jacksonObjectMapper().readValue<Map<String, String>>("<html>")
        }
        every { cs30.studentOverrides() } throws notJson

        assertEquals(1, mirror().call())

        assertTrue(err.contains(NOT_JSON_ERROR), err.toString())
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

    @Test
    fun `a cs30 course fragment the server finds one fit for is the course that is read`() {
        every { cs30.findCourses(CourseQuery("cs3")) } returns listOf(CourseRef("CS30", 2026, "Spring", 1))
        every { cs30.labPlan("CS30", 2026, "Spring", 1, 1) } returns plan
        every { cs30.bestSubmissions("CS30", 2026, "Spring", 1, 1, any()) } returns emptyList()

        val command = mirror().apply { code = "cs3"; year = null; semester = null; section = null }
        assertEquals(0, command.call())

        assertEquals("cs30 course: CS30 (Section 1, Semester Spring, Year 2026)", out.first())
        verify(exactly = 1) { cs30.labPlan("CS30", 2026, "Spring", 1, 1) }
        verify(exactly = 1) { cs30.bestSubmissions("CS30", 2026, "Spring", 1, 1, "babyshark") }
    }

    @Test
    fun `a fragment that fits several courses lists them and how to narrow, before anything else is read`() {
        every { cs30.findCourses(CourseQuery("cs30")) } returns listOf(
            CourseRef("CS30", 2026, "Spring", 1), CourseRef("CS30", 2026, "Spring", 2),
        )

        val command = mirror().apply { code = "cs30"; year = null; semester = null; section = null }
        assertEquals(1, command.call())

        assertEquals(
            listOf(
                "ERROR: multiple cs30 courses match code 'cs30':\n" +
                    "  - CS30 (Section 1, Semester Spring, Year 2026)\n" +
                    "  - CS30 (Section 2, Semester Spring, Year 2026)\n" +
                    "Narrow it with --cs30-year, --cs30-semester or --cs30-section."
            ),
            err,
        )
        verify(exactly = 0) { cs30.labPlan(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { canvas.findCourse(any()) }
    }

    @Test
    fun `a fragment that fits nothing lists the active courses to pick from`() {
        every { cs30.findCourses(CourseQuery("cs101")) } returns emptyList()
        every { cs30.findCourses(CourseQuery(active = true)) } returns listOf(CourseRef("CS46A", 2026, "Fall", 1))

        val command = mirror().apply { code = "cs101"; year = null; semester = null; section = null }
        assertEquals(1, command.call())

        assertEquals(
            listOf(
                "ERROR: no cs30 course matches code 'cs101'. Active courses:\n" +
                    "  - CS46A (Section 1, Semester Fall, Year 2026)"
            ),
            err,
        )
        verify(exactly = 0) { cs30.labPlan(any(), any(), any(), any(), any()) }
    }
}
