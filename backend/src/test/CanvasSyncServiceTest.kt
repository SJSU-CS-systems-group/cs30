import com.cs30.server.dto.CourseQuery
import com.cs30.server.dto.CourseRef
import com.cs30.server.models.Course
import com.cs30.server.models.Problem
import com.cs30.server.models.ScheduledLab
import com.cs30.server.repository.CourseRepository
import com.cs30.server.service.CanvasSyncService
import com.cs30.server.service.matchCourses
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDateTime

/**
 * The plan and submissions the Canvas endpoints hand to the CLI. The CLI can no longer read the
 * repo itself, so what this returns is all it ever sees - and the problem name it asks about
 * comes in over HTTP, so it must be checked against the lab before it can become a path.
 *
 * Also naming a course by fragment: the matching rules, and which courses a caller's search may
 * see at all.
 */
class CanvasSyncServiceTest {

    private lateinit var courseRepository: CourseRepository
    private lateinit var service: CanvasSyncService

    @TempDir
    lateinit var repo: File

    @BeforeEach
    fun setUp() {
        courseRepository = mockk()
        service = CanvasSyncService(courseRepository)
    }

    private fun course(studentGitRepo: String = repo.path): Course {
        val course = Course(
            code = "CS30", section = 1, year = 2026, semester = "Spring",
            studentGitRepo = studentGitRepo,
            students = mutableSetOf("zed@sjsu.edu", "amy@sjsu.edu"),
        )
        val lab = ScheduledLab(
            labNumber = 1,
            startDateTime = LocalDateTime.of(2026, 2, 10, 10, 0),
            endDateTime = LocalDateTime.of(2026, 2, 10, 11, 15),
        )
        lab.addProblem(Problem(name = "tenkinds", note = null))
        lab.addProblem(Problem(name = "babyshark", note = "Bonus problems"))
        lab.addProblem(Problem(name = "babyshark", note = "Bonus problems"))
        course.addLab(lab)
        return course
    }

    private fun stubCourse(course: Course?) {
        every { courseRepository.findByCodeAndYearAndSemesterAndSection("CS30", 2026, "Spring", 1) } returns course
    }

    /** Lays down what the server writes for a submission: the metadata file and the source it points at. */
    private fun writeSubmission(email: String, problem: String = "babyshark", passed: Int = 7, total: Int = 10) {
        val relative = "section_1/lab_1/$problem/$email/submissions"
        val dir = File(repo, relative).apply { mkdirs() }
        File(dir, "submission-2026-07-27T21-39-23.py").writeText("print(1)\n")
        File(dir, "bestsubmission.json").writeText(
            """{"highestPassed":$passed,"total":$total,"bestSubmissionPath":"$relative/submission-2026-07-27T21-39-23.py"}"""
        )
    }

    @Test
    fun `labPlan flattens the lab with sorted students and distinct sorted problems`() {
        stubCourse(course())

        val plan = service.labPlan("CS30", 2026, "Spring", 1, 1)

        assertEquals("CS30", plan.courseCode)
        assertEquals(1, plan.section)
        assertEquals(1, plan.labNumber)
        assertEquals(LocalDateTime.of(2026, 2, 10, 10, 0), plan.startDateTime)
        assertEquals(LocalDateTime.of(2026, 2, 10, 11, 15), plan.endDateTime)
        assertEquals(listOf("amy@sjsu.edu", "zed@sjsu.edu"), plan.studentEmails)
        assertEquals(listOf("babyshark", "tenkinds"), plan.problems.map { it.name })
        assertEquals("Bonus problems", plan.problems.first().note)
    }

    @Test
    fun `labPlan leaves the TA off the roster even when they are enrolled`() {
        // The TA may do labs in the student app and may also be on the roster; their work is
        // never graded, so they must never reach Canvas either.
        stubCourse(course().apply { taEmail = "amy@sjsu.edu" })

        val plan = service.labPlan("CS30", 2026, "Spring", 1, 1)

        assertEquals(listOf("zed@sjsu.edu"), plan.studentEmails)
    }

    @Test
    fun `labPlan names the missing course`() {
        stubCourse(null)

        val e = assertThrows(IllegalArgumentException::class.java) { service.labPlan("CS30", 2026, "Spring", 1, 1) }
        assertEquals("Course not found: CS30 (Section 1, Semester Spring, Year 2026)", e.message)
    }

    @Test
    fun `labPlan names the missing lab and lists the ones there are`() {
        stubCourse(course())

        val e = assertThrows(IllegalArgumentException::class.java) { service.labPlan("CS30", 2026, "Spring", 1, 9) }
        assertEquals("Lab 9 not found in CS30 section 1. Labs: 1", e.message)
    }

    @Test
    fun `bestSubmissions returns one entry per student that has one`() {
        stubCourse(course())
        writeSubmission("amy@sjsu.edu")

        val submissions = service.bestSubmissions("CS30", 2026, "Spring", 1, 1, "babyshark")

        assertEquals(1, submissions.size)
        val (email, best) = submissions.single()
        assertEquals("amy@sjsu.edu", email)
        assertEquals(7, best.highestPassed)
        assertEquals(10, best.total)
        assertEquals("submission-2026-07-27T21-39-23.py", best.fileName)
        assertEquals("print(1)\n", best.code)
        assertEquals("2026-07-27T21-39-23", best.submittedAt, "the timestamp comes from the file name")
    }

    @Test
    fun `bestSubmissions is empty when nobody submitted or the course has no repo`() {
        stubCourse(course())
        assertTrue(service.bestSubmissions("CS30", 2026, "Spring", 1, 1, "babyshark").isEmpty())

        stubCourse(course(studentGitRepo = ""))
        assertTrue(service.bestSubmissions("CS30", 2026, "Spring", 1, 1, "babyshark").isEmpty())
    }

    @Test
    fun `bestSubmissions refuses a problem that is not in the lab before touching any file`() {
        // A repo that does not exist: if the name were used as a path we would see a null/empty
        // result, not the exception - which is exactly the difference this test pins down.
        stubCourse(course(studentGitRepo = File(repo, "nowhere").path))

        for (name in listOf("unknown", "../../etc", "babyshark/../tenkinds")) {
            val e = assertThrows(IllegalArgumentException::class.java, { service.bestSubmissions("CS30", 2026, "Spring", 1, 1, name) }, name)
            assertTrue(e.message!!.startsWith("Problem '$name' is not in lab 1 of CS30 section 1"), e.message)
            assertTrue(e.message!!.contains("babyshark, tenkinds"), "the lab's problems are listed: ${e.message}")
        }
    }

    @Test
    fun `bestSubmission is null when the metadata points at a missing file`() {
        val dir = File(repo, "section_1/lab_1/babyshark/amy@sjsu.edu/submissions").apply { mkdirs() }
        File(dir, "bestsubmission.json").writeText("""{"highestPassed":1,"total":2,"bestSubmissionPath":"gone.py"}""")

        assertNull(service.bestSubmission(repo.path, 1, 1, "babyshark", "amy@sjsu.edu"))
    }

    // ---------------------------------------------------------------- naming a course by fragment

    private val future = LocalDateTime.of(2099, 1, 1, 0, 0)
    private val past = LocalDateTime.of(2000, 1, 1, 0, 0)

    private fun named(code: String, year: Int, semester: String, section: Int, endDate: LocalDateTime = future) =
        Course(code = code, year = year, semester = semester, section = section, endDate = endDate)

    @Test
    fun `a fragment of the code matches the course it fits`() {
        val cs30 = named("CS30", 2026, "Fall", 1)

        assertEquals(listOf(cs30), matchCourses(listOf(named("CS46A", 2026, "Fall", 1), cs30), CourseQuery("cs3")))
    }

    @Test
    fun `an exact code wins over a course whose code merely contains it`() {
        val cs30 = named("CS30", 2026, "Fall", 1)
        val cs30a = named("CS30A", 2026, "Fall", 1)

        assertEquals(listOf(cs30), matchCourses(listOf(cs30a, cs30), CourseQuery("cs30")))
        assertEquals(listOf(cs30a), matchCourses(listOf(cs30a, cs30), CourseQuery("cs30a")))
    }

    @Test
    fun `year, semester fragment and section narrow the match`() {
        val fall25 = named("CS30", 2025, "Fall", 1)
        val fall26s1 = named("CS30", 2026, "Fall", 1)
        val fall26s2 = named("CS30", 2026, "Fall", 2)
        val spring26 = named("CS30", 2026, "Spring", 1)
        val all = listOf(fall25, fall26s1, fall26s2, spring26)

        assertEquals(listOf(fall26s2), matchCourses(all, CourseQuery("cs30", year = 2026, semester = "fa", section = 2)))
        assertEquals(listOf(spring26), matchCourses(all, CourseQuery("cs30", semester = "spr")))
        assertEquals(listOf(fall25), matchCourses(all, CourseQuery("cs30", year = 2025)))
    }

    @Test
    fun `several fits are all returned, an excluding filter returns none, no filter is every course`() {
        val s1 = named("CS30", 2026, "Fall", 1)
        val s2 = named("CS30", 2026, "Fall", 2)
        val courses = listOf(s2, s1, named("CS46A", 2026, "Fall", 1))

        assertEquals(setOf(s1, s2), matchCourses(courses, CourseQuery("cs30")).toSet())
        assertTrue(matchCourses(courses, CourseQuery("cs30", section = 9)).isEmpty())
        assertEquals(3, matchCourses(courses, CourseQuery()).size)
    }

    @Test
    fun `findCourses returns refs in listing order, over all courses for the admin and own sections for a TA`() {
        every { courseRepository.findAll() } returns
            listOf(named("CS30", 2026, "Fall", 2), named("CS30", 2026, "Fall", 1))
        every { courseRepository.findByTaEmail("ta@sjsu.edu") } returns listOf(named("CS30", 2026, "Fall", 2))

        assertEquals(
            listOf(CourseRef("CS30", 2026, "Fall", 1), CourseRef("CS30", 2026, "Fall", 2)),
            service.findCourses(CourseQuery("cs30"), null),
        )
        assertEquals(
            listOf(CourseRef("CS30", 2026, "Fall", 2)),
            service.findCourses(CourseQuery("cs30"), "ta@sjsu.edu"),
        )
    }

    @Test
    fun `active keeps only the courses that have not ended, the same set the other commands suggest`() {
        val ended = named("CS30", 2024, "Fall", 1, endDate = past)
        val running = named("CS46A", 2026, "Fall", 1)
        every { courseRepository.findAll() } returns listOf(ended, running)
        every { courseRepository.findByEndDateAfter(any()) } returns listOf(running)

        assertEquals(listOf(CourseRef("CS46A", 2026, "Fall", 1)), service.findCourses(CourseQuery(active = true), null))
        assertEquals(2, service.findCourses(CourseQuery(), null).size)
    }
}
