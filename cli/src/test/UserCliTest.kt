package cli

import com.cs30.cli.AddStudent
import com.cs30.cli.CliOptions
import com.cs30.cli.FindCourse
import com.cs30.cli.FindStudent
import com.cs30.cli.RemoveCourse
import com.cs30.cli.RemoveStudent
import com.cs30.server.service.CourseService
import io.mockk.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.StringWriter

class UserCliTest {

    private lateinit var courseService: CourseService
    private lateinit var outWriter: StringWriter
    private lateinit var errWriter: StringWriter

    @BeforeEach
    fun setUp() {
        courseService = mockk(relaxed = true)
        outWriter = StringWriter()
        errWriter = StringWriter()
    }

    private fun createCliOptions(): CliOptions {
        val cliOptions = CliOptions()
        // We'll use a mock spec for testing
        return cliOptions
    }

    // ==================== AddStudent Tests ====================

    @Test
    fun `AddStudent should return 0 on success`() {
        // Given
        every { courseService.addStudentToCourse("CS-101", 2024, "Fall", 1, "student@test.edu") } returns
            "Added student student@test.edu to course CS-101 (Section 1)"

        val addStudent = AddStudent(courseService)
        addStudent.code = "CS-101"
        addStudent.year = 2024
        addStudent.semester = "Fall"
        addStudent.section = 1
        addStudent.email = "student@test.edu"

        // Mock cli
        val mockCli = mockk<CliOptions>(relaxed = true)
        every { mockCli.out(any<String>()) } just runs
        every { mockCli.err(any<String>()) } just runs
        addStudent.cli = mockCli

        // When
        val result = addStudent.call()

        // Then
        assertEquals(0, result)
        verify { mockCli.out("Added student student@test.edu to course CS-101 (Section 1)") }
    }

    @Test
    fun `AddStudent should return 1 on failure`() {
        // Given
        every { courseService.addStudentToCourse("CS-101", 2024, "Fall", 1, "student@test.edu") } returns
            "Course not found: CS-101 (Section 1)"

        val addStudent = AddStudent(courseService)
        addStudent.code = "CS-101"
        addStudent.year = 2024
        addStudent.semester = "Fall"
        addStudent.section = 1
        addStudent.email = "student@test.edu"

        val mockCli = mockk<CliOptions>(relaxed = true)
        every { mockCli.out(any<String>()) } just runs
        every { mockCli.err(any<String>()) } just runs
        addStudent.cli = mockCli

        // When
        val result = addStudent.call()

        // Then
        assertEquals(1, result)
        verify { mockCli.err("Course not found: CS-101 (Section 1)") }
    }

    // ==================== RemoveStudent Tests ====================

    @Test
    fun `RemoveStudent should return 0 on success`() {
        // Given
        every { courseService.removeStudentFromCourse("CS-101", 2024, "Fall", 1, "student@test.edu") } returns
            "Removed student student@test.edu from course CS-101 (Section 1)"

        val removeStudent = RemoveStudent(courseService)
        removeStudent.code = "CS-101"
        removeStudent.year = 2024
        removeStudent.semester = "Fall"
        removeStudent.section = 1
        removeStudent.email = "student@test.edu"

        val mockCli = mockk<CliOptions>(relaxed = true)
        removeStudent.cli = mockCli

        // When
        val result = removeStudent.call()

        // Then
        assertEquals(0, result)
        verify { mockCli.out("Removed student student@test.edu from course CS-101 (Section 1)") }
    }

    @Test
    fun `RemoveStudent should return 1 when student not found`() {
        // Given
        every { courseService.removeStudentFromCourse("CS-101", 2024, "Fall", 1, "student@test.edu") } returns
            "Student student@test.edu is not enrolled in CS-101 (Section 1)"

        val removeStudent = RemoveStudent(courseService)
        removeStudent.code = "CS-101"
        removeStudent.year = 2024
        removeStudent.semester = "Fall"
        removeStudent.section = 1
        removeStudent.email = "student@test.edu"

        val mockCli = mockk<CliOptions>(relaxed = true)
        removeStudent.cli = mockCli

        // When
        val result = removeStudent.call()

        // Then
        assertEquals(1, result)
        verify { mockCli.err("Student student@test.edu is not enrolled in CS-101 (Section 1)") }
    }

    // ==================== RemoveCourse Tests ====================

    @Test
    fun `RemoveCourse should return 0 when course deleted`() {
        // Given
        every { courseService.removeCourse("CS-101", 2024, "Fall", "1") } returns
            listOf("Deleted course CS-101 (Section 1)")

        val removeCourse = RemoveCourse(courseService)
        removeCourse.code = "CS-101"
        removeCourse.year = 2024
        removeCourse.semester = "Fall"
        removeCourse.section = "1"

        val mockCli = mockk<CliOptions>(relaxed = true)
        removeCourse.cli = mockCli

        // When
        val result = removeCourse.call()

        // Then
        assertEquals(0, result)
        verify { mockCli.out("Deleted course CS-101 (Section 1)") }
    }

    @Test
    fun `RemoveCourse should return 1 when course not ended yet`() {
        // Given
        every { courseService.removeCourse("CS-101", 2024, "Fall", "1") } returns
            listOf("Cannot delete course CS-101 (Section 1) because it has not ended yet")

        val removeCourse = RemoveCourse(courseService)
        removeCourse.code = "CS-101"
        removeCourse.year = 2024
        removeCourse.semester = "Fall"
        removeCourse.section = "1"

        val mockCli = mockk<CliOptions>(relaxed = true)
        removeCourse.cli = mockCli

        // When
        val result = removeCourse.call()

        // Then
        assertEquals(1, result)
        verify { mockCli.err("Cannot delete course CS-101 (Section 1) because it has not ended yet") }
    }

    // ==================== FindCourse Tests ====================

    @Test
    fun `FindCourse should return 0 when course found`() {
        // Given
        every { courseService.findCourse("CS-101", 2024, "Fall", "1") } returns
            listOf(
                "Course: CS-101 (Section 1)",
                "  Year: 2024",
                "  Students enrolled: 2",
                "    - student1@test.edu"
            )

        val findCourse = FindCourse(courseService)
        findCourse.code = "CS-101"
        findCourse.year = 2024
        findCourse.semester = "Fall"
        findCourse.section = "1"

        val mockCli = mockk<CliOptions>(relaxed = true)
        findCourse.cli = mockCli

        // When
        val result = findCourse.call()

        // Then
        assertEquals(0, result)
        verify { mockCli.out("Course: CS-101 (Section 1)") }
    }

    @Test
    fun `FindCourse should return 1 when course not found`() {
        // Given
        every { courseService.findCourse("CS-999", 2024, "Fall", "1") } returns
            listOf("ERROR: Course not found: CS-999 (Section 1)")

        val findCourse = FindCourse(courseService)
        findCourse.code = "CS-999"
        findCourse.year = 2024
        findCourse.semester = "Fall"
        findCourse.section = "1"

        val mockCli = mockk<CliOptions>(relaxed = true)
        findCourse.cli = mockCli

        // When
        val result = findCourse.call()

        // Then
        assertEquals(1, result)
        verify { mockCli.err("ERROR: Course not found: CS-999 (Section 1)") }
    }

    // ==================== FindStudent Tests ====================

    @Test
    fun `FindStudent should return 0 when student found in courses`() {
        // Given
        every { courseService.findStudent("student@test.edu") } returns
            listOf(
                "Student: student@test.edu",
                "Enrolled in 2 course(s):",
                "  - CS-101 (Section 1)",
                "  - CS-102 (Section 1)"
            )

        val findStudent = FindStudent(courseService)
        findStudent.email = "student@test.edu"

        val mockCli = mockk<CliOptions>(relaxed = true)
        findStudent.cli = mockCli

        // When
        val result = findStudent.call()

        // Then
        assertEquals(0, result)
        verify { mockCli.out("Student: student@test.edu") }
    }

    @Test
    fun `FindStudent should return 1 when student not found`() {
        // Given
        every { courseService.findStudent("unknown@test.edu") } returns
            listOf("ERROR: No courses found for student: unknown@test.edu")

        val findStudent = FindStudent(courseService)
        findStudent.email = "unknown@test.edu"

        val mockCli = mockk<CliOptions>(relaxed = true)
        findStudent.cli = mockCli

        // When
        val result = findStudent.call()

        // Then
        assertEquals(1, result)
        verify { mockCli.err("ERROR: No courses found for student: unknown@test.edu") }
    }
}
