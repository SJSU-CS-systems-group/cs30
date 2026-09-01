import com.cs30.server.models.Course
import com.cs30.server.repository.CourseRepository
import com.cs30.server.repository.TaSessionRepository
import com.cs30.server.service.TaIdentityService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Course scoping for the TA dashboard. Every scoping site and ownership check in TaController,
 * LabHealthController and CliTokenController funnels through getCoursesForTa, so this one method
 * decides what the dashboard shows — and it is what grants the configured admin the whole thing.
 */
class TaIdentityServiceTest {

    private lateinit var taSessionRepository: TaSessionRepository
    private lateinit var courseRepository: CourseRepository

    private val ta = "ta@sjsu.edu"
    private val admin = "admin@sjsu.edu"
    private val stranger = "stranger@sjsu.edu"

    private val taCourse = Course(id = "c1", code = "CS30", section = 1)
    private val otherCourse = Course(id = "c2", code = "CS30", section = 2)

    @BeforeEach
    fun setUp() {
        taSessionRepository = mockk(relaxed = true)
        courseRepository = mockk(relaxed = true)
        // A relaxed mock answers an unstubbed List-returning call with a mock List, not an empty one.
        every { courseRepository.findByTaEmail(any()) } returns emptyList()
        every { courseRepository.findByTaEmail(ta) } returns listOf(taCourse)
        every { courseRepository.findAllWithStudents() } returns listOf(taCourse, otherCourse)
    }

    private fun service(adminEmail: String) =
        TaIdentityService(taSessionRepository, courseRepository, adminEmail)

    @Test
    fun `TA gets only their own course`() {
        assertEquals(listOf(taCourse), service(admin).getCoursesForTa(ta))
    }

    @Test
    fun `configured admin gets every course`() {
        assertEquals(listOf(taCourse, otherCourse), service(admin).getCoursesForTa(admin))
    }

    @Test
    fun `admin match is case-insensitive`() {
        assertEquals(listOf(taCourse, otherCourse), service(admin).getCoursesForTa("ADMIN@sjsu.edu"))
    }

    /** admin-email is unset outside deploy/, so it defaults to blank — it must not match anyone. */
    @Test
    fun `blank admin-email grants nothing`() {
        assertEquals(emptyList<Course>(), service("").getCoursesForTa(""))
        assertEquals(emptyList<Course>(), service("").getCoursesForTa(stranger))
    }

    @Test
    fun `unrelated email gets nothing`() {
        assertEquals(emptyList<Course>(), service(admin).getCoursesForTa(stranger))
    }
}
