import com.cs30.server.models.Course
import com.cs30.server.models.ScheduledLab
import com.cs30.server.repository.CourseRepository
import com.cs30.server.service.CourseService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class CourseServiceTest {

    private lateinit var courseRepository: CourseRepository
    private lateinit var courseService: CourseService

    @BeforeEach
    fun setUp() {
        courseRepository = mockk(relaxed = true)
        courseService = CourseService(courseRepository)
    }

    @Test
    fun `createCourseWithStudents should save course with students and labs`() {
        // Given
        val students = listOf("student1@test.edu", "student2@test.edu")
        val labs = listOf(
            ScheduledLab(1, LocalDateTime.of(2024, 9, 2, 10, 0), LocalDateTime.of(2024, 9, 2, 11, 15))
        )
        every { courseRepository.save(any()) } answers { firstArg() }

        // When
        courseService.createCourseWithStudents(
            courseName = "CS-101",
            courseSection = 1,
            year = 2024,
            semester = "Fall",
            startDate = LocalDateTime.of(2024, 9, 1, 0, 0),
            endDate = LocalDateTime.of(2024, 12, 15, 0, 0),
            studentGitRepo = "/home/user/git/cs101-students",
            problemGitRepo = "/home/user/git/cs101-problems",
            language = "Java",
            students = students,
            labs = labs
        )

        // Then
        verify {
            courseRepository.save(match { course ->
                course.code == "CS-101" &&
                        course.section == 1 &&
                        course.year == 2024 &&
                        course.semester == "Fall" &&
                        course.students.containsAll(students) &&
                        course.labs.size == 1
            })
        }
    }

    @Test
    fun `addStudentToCourse should return success when course exists and student not enrolled`() {
        // Given
        val course = Course(
            code = "CS-101",
            section = 1,
            year = 2024,
            semester = "Fall"
        )
        every { courseRepository.findByCodeAndYearAndSemesterAndSection("CS-101", 2024, "Fall", 1) } returns course
        every { courseRepository.save(any()) } answers { firstArg() }

        // When
        val result = courseService.addStudentToCourse("CS-101", 2024, "Fall", 1, "newstudent@test.edu")

        // Then
        Assertions.assertEquals("Added student newstudent@test.edu to course CS-101 (Section 1, Semester Fall, Year 2024)", result)
        Assertions.assertTrue(course.students.contains("newstudent@test.edu"))
    }

    @Test
    fun `addStudentToCourse should return error when course not found`() {
        // Given
        every { courseRepository.findByCodeAndYearAndSemesterAndSection("CS-999", 2024, "Fall", 1) } returns null

        // When
        val result = courseService.addStudentToCourse("CS-999", 2024, "Fall", 1, "student@test.edu")

        // Then
        Assertions.assertEquals("Course not found: CS-999 (Section 1, Semester Fall, Year 2024)", result)
    }

    @Test
    fun `addStudentToCourse should return error when student already enrolled`() {
        // Given
        val course = Course(
            code = "CS-101",
            section = 1,
            year = 2024,
            semester = "Fall"
        )
        course.students.add("existing@test.edu")
        every { courseRepository.findByCodeAndYearAndSemesterAndSection("CS-101", 2024, "Fall", 1) } returns course

        // When
        val result = courseService.addStudentToCourse("CS-101", 2024, "Fall", 1, "existing@test.edu")

        // Then
        Assertions.assertEquals("Student existing@test.edu is already enrolled in CS-101 (Section 1, Semester Fall, Year 2024)", result)
    }

    @Test
    fun `removeStudentFromCourse should return success when student is enrolled`() {
        // Given
        val course = Course(
            code = "CS-101",
            section = 1,
            year = 2024,
            semester = "Fall"
        )
        course.students.add("student@test.edu")
        every { courseRepository.findByCodeAndYearAndSemesterAndSection("CS-101", 2024, "Fall", 1) } returns course
        every { courseRepository.save(any()) } answers { firstArg() }

        // When
        val result = courseService.removeStudentFromCourse("CS-101", 2024, "Fall", 1, "student@test.edu")

        // Then
        Assertions.assertEquals("Removed student student@test.edu from course CS-101 (Section 1, Semester Fall, Year 2024)", result)
        Assertions.assertFalse(course.students.contains("student@test.edu"))
    }

    @Test
    fun `removeStudentFromCourse should return error when student not enrolled`() {
        // Given
        val course = Course(
            code = "CS-101",
            section = 1,
            year = 2024,
            semester = "Fall"
        )
        every { courseRepository.findByCodeAndYearAndSemesterAndSection("CS-101", 2024, "Fall", 1) } returns course

        // When
        val result = courseService.removeStudentFromCourse("CS-101", 2024, "Fall", 1, "notexist@test.edu")

        // Then
        Assertions.assertEquals("Student notexist@test.edu is not enrolled in CS-101 (Section 1, Semester Fall, Year 2024)", result)
    }

    @Test
    fun `findCourse should return course details when found`() {
        // Given
        val course = Course(
            code = "CS-101",
            section = 1,
            year = 2024,
            semester = "Fall",
            startDate = LocalDateTime.of(2024, 9, 1, 0, 0),
            endDate = LocalDateTime.of(2024, 12, 15, 0, 0),
            problemGitRepo = ""
        )
        course.students.add("student1@test.edu")
        every { courseRepository.findByCodeAndYearAndSemesterAndSection("CS-101", 2024, "Fall", 1) } returns course

        // When
        val results = courseService.findCourse("CS-101", 2024, "Fall", "1")

        // Then
        Assertions.assertTrue(results.any { it.contains("CS-101") })
        Assertions.assertTrue(results.any { it.contains("student1@test.edu") })
    }

    @Test
    fun `findCourse should return error when not found`() {
        // Given
        every { courseRepository.findByCodeAndYearAndSemesterAndSection("CS-999", 2024, "Fall", 1) } returns null

        // When
        val results = courseService.findCourse("CS-999", 2024, "Fall", "1")

        // Then
        Assertions.assertTrue(results.first().startsWith("ERROR:"))
    }

    @Test
    fun `findStudent should return courses when student is enrolled`() {
        // Given
        val course1 = Course(code = "CS-101", section = 1, year = 2024, semester = "Fall")
        val course2 = Course(code = "CS-102", section = 1, year = 2024, semester = "Fall")
        every { courseRepository.findByStudentEmail("student@test.edu") } returns listOf(course1, course2)

        // When
        val results = courseService.findStudent("student@test.edu")

        // Then
        Assertions.assertTrue(results.any { it.contains("student@test.edu") })
        Assertions.assertTrue(results.any { it.contains("2 course(s)") })
    }

    @Test
    fun `findStudent should return error when no courses found`() {
        // Given
        every { courseRepository.findByStudentEmail("unknown@test.edu") } returns emptyList()

        // When
        val results = courseService.findStudent("unknown@test.edu")

        // Then
        Assertions.assertTrue(results.first().startsWith("ERROR:"))
    }

    @Test
    fun `removeCourse should delete course when past end date`() {
        // Given
        val course = Course(
            code = "CS-101",
            section = 1,
            year = 2024,
            semester = "Fall",
            endDate = LocalDateTime.of(2020, 12, 15, 0, 0) // Past date
        )
        every { courseRepository.findByCodeAndYearAndSemesterAndSection("CS-101", 2024, "Fall", 1) } returns course
        every { courseRepository.delete(any()) } just runs

        // When
        val results = courseService.removeCourse("CS-101", 2024, "Fall", "1")

        // Then
        Assertions.assertTrue(results.any { it.startsWith("Deleted") })
        verify { courseRepository.delete(course) }
    }

    @Test
    fun `removeCourse should not delete course when not past end date`() {
        // Given
        val course = Course(
            code = "CS-101",
            section = 1,
            year = 2024,
            semester = "Fall",
            endDate = LocalDateTime.of(2099, 12, 15, 0, 0) // Future date
        )
        every { courseRepository.findByCodeAndYearAndSemesterAndSection("CS-101", 2024, "Fall", 1) } returns course

        // When
        val results = courseService.removeCourse("CS-101", 2024, "Fall", "1")

        // Then
        Assertions.assertTrue(results.any { it.contains("Cannot delete") })
        verify(exactly = 0) { courseRepository.delete(any()) }
    }
}