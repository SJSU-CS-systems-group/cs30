import com.cs30.server.models.Course
import com.cs30.server.models.ScheduledLab
import com.cs30.server.repository.CourseRepository
import com.cs30.server.service.CourseAccessService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Who may use the student app for a course, and when: an enrolled student only inside the lab
 * window, the course's TA at any time. Every student-facing gate (problem list and content, run,
 * submit, autosave, lab list) goes through this class, so these are the rules those endpoints
 * inherit.
 */
class CourseAccessServiceTest {

    private lateinit var courseRepository: CourseRepository
    private lateinit var access: CourseAccessService

    private val student = "student@sjsu.edu"
    private val ta = "ta@sjsu.edu"
    private val stranger = "stranger@sjsu.edu"

    @BeforeEach
    fun setUp() {
        courseRepository = mockk(relaxed = true)
        access = CourseAccessService(courseRepository)
        // A relaxed mock answers an unstubbed List-returning call with a mock List, not an empty one.
        every { courseRepository.findByStudentEmail(any()) } returns emptyList()
        every { courseRepository.findByTaEmail(any()) } returns emptyList()
    }

    private fun now(): LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)
    private fun pastLab() = ScheduledLab(labNumber = 1, startDateTime = now().minusHours(3), endDateTime = now().minusHours(1))
    private fun activeLab() = ScheduledLab(labNumber = 2, startDateTime = now().minusHours(1), endDateTime = now().plusHours(1))
    private fun futureLab() = ScheduledLab(labNumber = 3, startDateTime = now().plusHours(1), endDateTime = now().plusHours(3))

    /** One student on the roster, one TA, and a lab in each of the three states. */
    private fun course(id: String = "course-1", taEmail: String? = ta): Course {
        val course = Course(id = id, code = "CS30", section = 1, taEmail = taEmail)
        course.students.add(student)
        course.addLab(pastLab())
        course.addLab(activeLab())
        course.addLab(futureLab())
        every { courseRepository.existsByIdAndStudentsContaining(id, student) } returns true
        return course
    }

    // ==================== coursesFor ====================

    @Test
    fun `coursesFor unions student and TA courses without duplicates`() {
        val enrolledAndTa = course("course-1")
        val taOnly = course("course-2")
        every { courseRepository.findByStudentEmail(ta) } returns listOf(enrolledAndTa)
        every { courseRepository.findByTaEmail(ta) } returns listOf(enrolledAndTa, taOnly)

        val courses = access.coursesFor(ta)

        assertEquals(2, courses.size)
        assertTrue(courses.containsAll(listOf(enrolledAndTa, taOnly)))
    }

    @Test
    fun `coursesFor is empty for an email that is neither student nor TA`() {
        assertTrue(access.coursesFor(stranger).isEmpty())
    }

    // ==================== isTa / isMember ====================

    @Test
    fun `isTa matches taEmail case-insensitively`() {
        val course = course(taEmail = "TA@SJSU.edu")

        assertTrue(access.isTa(course, "ta@sjsu.edu"))
        assertFalse(access.isTa(course, student))
    }

    @Test
    fun `isTa is false when the course has no TA`() {
        assertFalse(access.isTa(course(taEmail = null), ta))
    }

    @Test
    fun `isMember is true for an enrolled student and the TA and false for a stranger`() {
        val course = course()

        assertTrue(access.isMember(course, student))
        assertTrue(access.isMember(course, ta), "the TA is a member without being on the roster")
        assertFalse(access.isMember(course, stranger))
    }

    // ==================== canAccessLab / visibleLabs ====================

    @Test
    fun `canAccessLab holds a student to the lab window`() {
        val course = course()

        assertFalse(access.canAccessLab(course, pastLab(), student))
        assertTrue(access.canAccessLab(course, activeLab(), student))
        assertFalse(access.canAccessLab(course, futureLab(), student))
    }

    @Test
    fun `canAccessLab lets the TA in before during and after the window`() {
        val course = course()

        assertTrue(access.canAccessLab(course, pastLab(), ta))
        assertTrue(access.canAccessLab(course, activeLab(), ta))
        assertTrue(access.canAccessLab(course, futureLab(), ta))
    }

    @Test
    fun `visibleLabs is the active labs for a student and every lab for the TA`() {
        val course = course()

        assertEquals(listOf(2), access.visibleLabs(course, student).map { it.labNumber })
        assertEquals(listOf(1, 2, 3), access.visibleLabs(course, ta).map { it.labNumber })
    }

    // ==================== labDenialReason ====================

    @Test
    fun `labDenialReason names a missing lab for everyone`() {
        val course = course()

        assertEquals("Lab 99 not found", access.labDenialReason(course, 99, student))
        assertEquals("Lab 99 not found", access.labDenialReason(course, 99, ta))
    }

    @Test
    fun `labDenialReason tells a student why they are refused`() {
        val course = course()

        assertEquals("Lab deadline has passed", access.labDenialReason(course, 1, student))
        assertNull(access.labDenialReason(course, 2, student))
        assertEquals("Lab has not started yet", access.labDenialReason(course, 3, student))
    }

    @Test
    fun `labDenialReason is null for the TA outside the window`() {
        val course = course()

        assertNull(access.labDenialReason(course, 1, ta))
        assertNull(access.labDenialReason(course, 3, ta))
    }
}
