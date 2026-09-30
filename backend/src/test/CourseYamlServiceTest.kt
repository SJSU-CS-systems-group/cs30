import com.cs30.server.dto.CourseInput
import com.cs30.server.models.Course
import com.cs30.server.models.Problem
import com.cs30.server.models.ScheduledLab
import com.cs30.server.repository.CourseRepository
import com.cs30.server.service.AppTimeZoneService
import com.cs30.server.service.CourseYamlService
import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class CourseYamlServiceTest {

    // A real zone service: these tests are about the UTC <-> app-zone conversion.
    private val appTimeZoneService = AppTimeZoneService("America/Los_Angeles")
    private lateinit var courseRepository: CourseRepository
    private lateinit var service: CourseYamlService

    @BeforeEach
    fun setUp() {
        courseRepository = mockk(relaxed = true)
        service = CourseYamlService(courseRepository, appTimeZoneService)
    }

    /** 2026-08-27, 16:43 PDT, stored as UTC. */
    private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        appTimeZoneService.toUtc(LocalDateTime.of(y, mo, d, h, mi))

    private fun course(
        section: Int,
        students: Collection<String> = listOf("b@sjsu.edu", "a@sjsu.edu"),
        tas: Collection<String> = listOf("ta@sjsu.edu"),
    ): Course {
        val course = Course(
            id = "course-$section",
            code = "CMPE30",
            section = section,
            year = 2026,
            semester = "Fall",
            startDate = utc(2026, 8, 19, 0, 0),
            endDate = utc(2026, 12, 15, 0, 0),
            language = "Java",
            studentGitRepo = "/repos/students",
            problemGitRepo = "/repos/problems",
        )
        tas.forEach { course.taEmails.add(it) }
        students.forEach { course.students.add(it) }
        val lab = ScheduledLab(
            id = "lab-$section-1",
            labNumber = 1,
            startDateTime = utc(2026, 8, 27, 16, 43),
            endDateTime = utc(2026, 8, 27, 18, 0),
        )
        lab.addProblem(Problem(id = "p2", name = "quoted", language = "Java"))
        lab.addProblem(Problem(id = "p1", name = "babyshark", language = "Python", note = "warmup"))
        course.addLab(lab)
        return course
    }

    @Test
    fun `render then parse yields an equal document`() {
        val sections = listOf(course(1), course(2))

        val parsed: CourseInput = service.mapper.readValue(service.render(sections))

        assertEquals(service.toInput(sections), parsed)
    }

    @Test
    fun `lab times always carry seconds so addcourse can parse them back`() {
        // LocalDateTime.toString() drops :00, which the yyyy-MM-dd'T'HH:mm:ss pattern won't parse.
        val yaml = service.render(listOf(course(1)))

        assertTrue(yaml.contains("2026-08-27T16:43:00"), "lab start must keep its seconds, got:\n$yaml")
        assertTrue(yaml.contains("2026-08-27T18:00:00"), "lab end must keep its seconds, got:\n$yaml")
    }

    @Test
    fun `render is byte-stable regardless of collection order`() {
        val ascending = listOf(course(1, students = listOf("a@sjsu.edu", "b@sjsu.edu")), course(2))
        val descending = listOf(course(2), course(1, students = listOf("b@sjsu.edu", "a@sjsu.edu")))

        assertEquals(service.render(ascending), service.render(descending))
    }

    @Test
    fun `export then import then export is a fixed point`() {
        val sections = listOf(course(1), course(2))
        val first = service.render(sections)

        val parsed: CourseInput = service.mapper.readValue(first)
        val reimported = parsed.sections.map { sectionInput ->
            val course = Course(
                id = "course-${sectionInput.number}",
                code = parsed.code,
                section = sectionInput.number,
                year = parsed.year,
                semester = parsed.semester,
                startDate = appTimeZoneService.toUtc(parsed.startDate.atStartOfDay()),
                endDate = appTimeZoneService.toUtc(parsed.endDate.atStartOfDay()),
                language = parsed.language,
                studentGitRepo = parsed.studentGitRepo,
                problemGitRepo = parsed.problemGitRepo,
            )
            sectionInput.taEmails().forEach { course.taEmails.add(it) }
            sectionInput.students.forEach { course.students.add(it) }
            sectionInput.labs.forEach { labInput ->
                val lab = ScheduledLab(
                    id = "lab-${sectionInput.number}-${labInput.number}",
                    labNumber = labInput.number,
                    startDateTime = appTimeZoneService.toUtc(labInput.startDateTime),
                    endDateTime = appTimeZoneService.toUtc(labInput.endDateTime),
                )
                labInput.problems.forEach { p ->
                    lab.addProblem(Problem(id = p.name, name = p.name, language = p.language ?: parsed.language, note = p.note))
                }
                course.addLab(lab)
            }
            course
        }

        assertEquals(first, service.render(reimported))
    }

    @Test
    fun `the legacy single-ta key is never written, and a null note is omitted`() {
        val yaml = service.render(listOf(course(1, tas = emptyList())))

        // An empty section renders `tas: []`, like students and labs - NON_NULL drops nulls, not
        // empties. What must never appear is the pre-multi-TA singular key.
        assertTrue(yaml.contains("tas: []"), "a section with no TAs should render an empty list, got:\n$yaml")
        assertFalse(Regex("^\\s*ta:", RegexOption.MULTILINE).containsMatchIn(yaml), "singular ta: must never be written, got:\n$yaml")
        assertFalse(yaml.contains("note: null"), "a problem with no note should omit the key, got:\n$yaml")
        assertTrue(yaml.contains("note: \"warmup\"") || yaml.contains("note: warmup"), yaml)
    }

    @Test
    fun `a section's TAs render sorted under tas`() {
        val yaml = service.render(listOf(course(1, tas = listOf("zoe@sjsu.edu", "amy@sjsu.edu"))))

        assertTrue(yaml.contains("tas:"), yaml)
        assertTrue(
            yaml.indexOf("amy@sjsu.edu") < yaml.indexOf("zoe@sjsu.edu"),
            "TAs are a Set, so they must be sorted or the file churns commits, got:\n$yaml"
        )
        val parsed: CourseInput = service.mapper.readValue(yaml)
        assertEquals(listOf("amy@sjsu.edu", "zoe@sjsu.edu"), parsed.sections.single().taEmails())
    }
}
