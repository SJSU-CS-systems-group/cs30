import com.cs30.server.models.Course
import com.cs30.server.repository.CourseRepository
import com.cs30.server.repository.TaSessionRepository
import com.cs30.server.models.TaSession
import com.cs30.server.service.TaAccess
import com.cs30.server.service.TaIdentityService
import com.cs30.server.service.denied
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
        // Same for Optional: an unstubbed findById would hand back a mock session, not an absent one.
        every { taSessionRepository.findById(any()) } returns java.util.Optional.empty()
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

    // ==================== authorize ====================

    /**
     * TA status is settled per request, not just at login: a session outlives a removal from the
     * course, so the endpoints have to ask again rather than trust the token alone.
     */
    private fun withSession(email: String) {
        every { taSessionRepository.findById("tok") } returns
            java.util.Optional.of(TaSession(token = "tok", email = email))
    }

    @Test
    fun `a TA with a course is granted, with the courses attached`() {
        withSession(ta)
        val access = service(admin).authorize("Bearer tok")
        assertTrue(access is TaAccess.Granted, "expected Granted, got $access")
        assertEquals(listOf(taCourse), (access as TaAccess.Granted).courses)
        assertEquals(ta, access.email)
    }

    @Test
    fun `a live session for someone who is no longer a TA is refused`() {
        withSession(stranger)
        assertEquals(TaAccess.NotATa, service(admin).authorize("Bearer tok"))
    }

    @Test
    fun `no session is unauthenticated, not merely forbidden`() {
        assertEquals(TaAccess.Unauthenticated, service(admin).authorize(null))
        assertEquals(TaAccess.Unauthenticated, service(admin).authorize("Bearer unknown"))
    }

    @Test
    fun `the admin is granted even with no course of their own`() {
        withSession(admin)
        val access = service(admin).authorize("Bearer tok")
        assertTrue(access is TaAccess.Granted, "the admin must keep the whole dashboard, got $access")
        assertEquals(listOf(taCourse, otherCourse), (access as TaAccess.Granted).courses)
    }

    @Test
    fun `denied maps no session to 401 and no course to 403`() {
        assertEquals(401, TaAccess.Unauthenticated.denied<Unit>().statusCode.value())
        assertEquals(403, TaAccess.NotATa.denied<Unit>().statusCode.value())
    }
}
