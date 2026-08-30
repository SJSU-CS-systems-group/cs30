import com.cs30.server.dto.CourseQuery
import com.cs30.server.dto.CourseRef
import com.cs30.server.models.Course
import com.cs30.server.repository.CourseRepository
import com.cs30.server.service.CanvasSyncService
import com.cs30.server.service.matchCourse
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * Naming a cs30 course by fragment: the matching rules, the messages a miss or an ambiguous match
 * produce, and which courses a caller's search may see at all.
 */
class Cs30CourseMatchTest {

    private val future = LocalDateTime.of(2099, 1, 1, 0, 0)
    private val past = LocalDateTime.of(2000, 1, 1, 0, 0)

    private fun course(code: String, year: Int, semester: String, section: Int, endDate: LocalDateTime = future) =
        Course(code = code, year = year, semester = semester, section = section, endDate = endDate)

    private fun failure(courses: List<Course>, query: CourseQuery): String =
        assertThrows(IllegalArgumentException::class.java) { matchCourse(courses, query) }.message!!

    @Test
    fun `a fragment of the code resolves when it fits one course`() {
        val cs30 = course("CS30", 2026, "Fall", 1)

        assertEquals(cs30, matchCourse(listOf(course("CS46A", 2026, "Fall", 1), cs30), CourseQuery("cs3")))
    }

    @Test
    fun `an exact code wins over a course whose code merely contains it`() {
        val cs30 = course("CS30", 2026, "Fall", 1)
        val cs30a = course("CS30A", 2026, "Fall", 1)

        assertEquals(cs30, matchCourse(listOf(cs30a, cs30), CourseQuery("cs30")))
        assertEquals(cs30a, matchCourse(listOf(cs30a, cs30), CourseQuery("cs30a")))
    }

    @Test
    fun `year, semester fragment and section narrow the match`() {
        val fall25 = course("CS30", 2025, "Fall", 1)
        val fall26s1 = course("CS30", 2026, "Fall", 1)
        val fall26s2 = course("CS30", 2026, "Fall", 2)
        val spring26 = course("CS30", 2026, "Spring", 1)
        val all = listOf(fall25, fall26s1, fall26s2, spring26)

        assertEquals(fall26s2, matchCourse(all, CourseQuery("cs30", year = 2026, semester = "fa", section = 2)))
        assertEquals(spring26, matchCourse(all, CourseQuery("cs30", semester = "spr")))
        assertEquals(fall25, matchCourse(all, CourseQuery("cs30", year = 2025)))
    }

    @Test
    fun `several matches are an error that lists them and how to narrow`() {
        val courses = listOf(
            course("CS30", 2026, "Fall", 2), course("CS30", 2026, "Fall", 1), course("CS46A", 2026, "Fall", 1),
        )

        val message = failure(courses, CourseQuery("cs30"))

        assertEquals(
            "multiple cs30 courses match code 'cs30':\n" +
                "  - CS30 (Section 1, Semester Fall, Year 2026)\n" +
                "  - CS30 (Section 2, Semester Fall, Year 2026)\n" +
                "Narrow it with --cs30-year, --cs30-semester or --cs30-section.",
            message,
        )
    }

    @Test
    fun `no match lists only the courses that have not ended`() {
        val courses = listOf(course("CS30", 2024, "Fall", 1, endDate = past), course("CS46A", 2026, "Fall", 1))

        val message = failure(courses, CourseQuery("cs101", semester = "fall"))

        assertEquals(
            "no cs30 course matches code 'cs101', semester 'fall'. Active courses:\n" +
                "  - CS46A (Section 1, Semester Fall, Year 2026)",
            message,
        )
        assertFalse(message.contains("2024"), message)
    }

    @Test
    fun `no match with nothing active says so rather than listing nothing`() {
        val courses = listOf(course("CS30", 2024, "Fall", 1, endDate = past))

        assertEquals(
            "no cs30 course matches code 'cs101'. Active courses: (none)",
            failure(courses, CourseQuery("cs101")),
        )
    }

    @Test
    fun `a narrowing filter that excludes everything is a miss, not a partial match`() {
        val message = failure(listOf(course("CS30", 2026, "Fall", 1)), CourseQuery("cs30", section = 9))

        assertTrue(message.startsWith("no cs30 course matches code 'cs30', section 9."), message)
    }

    @Test
    fun `the admin searches every course and a TA only their own sections`() {
        val repository = mockk<CourseRepository>()
        val service = CanvasSyncService(repository)
        every { repository.findAll() } returns listOf(course("CS30", 2026, "Fall", 1), course("CS30", 2026, "Fall", 2))
        every { repository.findByTaEmail("ta@sjsu.edu") } returns listOf(course("CS30", 2026, "Fall", 2))

        assertEquals(CourseRef("CS30", 2026, "Fall", 2), service.findCourse(CourseQuery("cs30"), "ta@sjsu.edu"))

        val message = assertThrows(IllegalArgumentException::class.java) {
            service.findCourse(CourseQuery("cs30"), null)
        }.message!!
        assertTrue(message.startsWith("multiple cs30 courses match code 'cs30':"), message)
        verify(exactly = 1) { repository.findAll() }
        verify(exactly = 1) { repository.findByTaEmail("ta@sjsu.edu") }
    }
}
