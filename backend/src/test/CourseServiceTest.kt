import com.cs30.server.dto.CourseInput
import com.cs30.server.dto.LabInput
import com.cs30.server.dto.ProblemInput
import com.cs30.server.dto.SectionInput
import com.cs30.server.models.Course
import com.cs30.server.models.ScheduledLab
import com.cs30.server.repository.CourseRepository
import com.cs30.server.repository.LoginSessionRepository
import com.cs30.server.service.AppTimeZoneService
import com.cs30.server.service.CourseService
import com.cs30.server.service.CourseYamlSyncService
import com.cs30.server.service.CourseYamlTarget
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class CourseServiceTest {

    private lateinit var courseRepository: CourseRepository
    private lateinit var loginSessionRepository: LoginSessionRepository
    private lateinit var courseYamlSync: CourseYamlSyncService
    private lateinit var courseService: CourseService

    @BeforeEach
    fun setUp() {
        courseRepository = mockk(relaxed = true)
        loginSessionRepository = mockk(relaxed = true)
        courseYamlSync = mockk(relaxed = true)
        // A relaxed mock can't satisfy save's generic <S : Course> return type; hand the entity back.
        every { courseRepository.save(any()) } answers { firstArg() }
        courseService = CourseService(
            courseRepository,
            loginSessionRepository,
            AppTimeZoneService("America/Los_Angeles"),
            courseYamlSync,
        )
    }

    @Test
    fun `createCourseWithStudents should save course with students and labs`() {
        // Given
        val students = listOf("student1@test.edu", "student2@test.edu")
        val labs = listOf(
            ScheduledLab(labNumber = 1, startDateTime = LocalDateTime.of(2024, 9, 2, 10, 0), endDateTime = LocalDateTime.of(2024, 9, 2, 11, 15))
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
            taEmails = listOf("ta@test.edu"),
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
        every { loginSessionRepository.deleteByCourseId(any()) } just runs

        // When
        val results = courseService.removeCourse("CS-101", 2024, "Fall", "1")

        // Then
        Assertions.assertTrue(results.any { it.startsWith("Deleted") })
        verify { courseRepository.delete(course) }
        verify { loginSessionRepository.deleteByCourseId(course.id) }
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

    @Test
    fun `removeCourse should call deleteByCourseId even when course has no students enrolled`() {
        // The old implementation guarded deletion with if (students.isNotEmpty()).
        // The new implementation always calls deleteByCourseId — safe because the SQL
        // returns 0 rows for an empty course, and we must not skip the call.
        val course = Course(
            code = "CS-101",
            section = 1,
            year = 2024,
            semester = "Fall",
            endDate = LocalDateTime.of(2020, 12, 15, 0, 0)
        )
        // No students added to course
        every { courseRepository.findByCodeAndYearAndSemesterAndSection("CS-101", 2024, "Fall", 1) } returns course
        every { courseRepository.delete(any()) } just runs
        every { loginSessionRepository.deleteByCourseId(any()) } just runs

        val results = courseService.removeCourse("CS-101", 2024, "Fall", "1")

        Assertions.assertTrue(results.any { it.startsWith("Deleted") })
        verify { loginSessionRepository.deleteByCourseId(course.id) }
    }

    @Test
    fun `removeCourse with section=all should delete all matching past courses`() {
        val course1 = Course(code = "CS-101", section = 1, year = 2024, semester = "Fall",
            endDate = LocalDateTime.of(2020, 12, 15, 0, 0))
        val course2 = Course(code = "CS-101", section = 2, year = 2024, semester = "Fall",
            endDate = LocalDateTime.of(2020, 12, 15, 0, 0))
        every { courseRepository.findByCodeAndYearAndSemester("CS-101", 2024, "Fall") } returns listOf(course1, course2)
        every { courseRepository.delete(any()) } just runs
        every { loginSessionRepository.deleteByCourseId(any()) } just runs

        val results = courseService.removeCourse("CS-101", 2024, "Fall", "all")

        Assertions.assertEquals(2, results.filter { it.startsWith("Deleted") }.size)
        verify { loginSessionRepository.deleteByCourseId(course1.id) }
        verify { loginSessionRepository.deleteByCourseId(course2.id) }
    }

    // ==================== TAs ====================

    /** A section may have several TAs, so addTA adds and removeTA names the one to drop. */
    private fun taCourse(vararg tas: String): Course {
        val course = Course(code = "CS-101", section = 1, year = 2024, semester = "Fall",
            taEmails = tas.toMutableSet())
        every { courseRepository.findByCodeAndYearAndSemesterAndSection("CS-101", 2024, "Fall", 1) } returns course
        every { courseRepository.save(any()) } answers { firstArg() }
        return course
    }

    @Test
    fun `addTA keeps the TAs already assigned`() {
        val course = taCourse("first@test.edu")

        val result = courseService.addTA("CS-101", 2024, "Fall", 1, "second@test.edu")

        Assertions.assertTrue(result.startsWith("Added"), result)
        Assertions.assertEquals(setOf("first@test.edu", "second@test.edu"), course.taEmails)
    }

    @Test
    fun `addTA refuses a duplicate regardless of case`() {
        val course = taCourse("First@Test.edu")

        val result = courseService.addTA("CS-101", 2024, "Fall", 1, "first@test.edu")

        Assertions.assertTrue(result.contains("already assigned"), result)
        Assertions.assertEquals(1, course.taEmails.size)
    }

    @Test
    fun `removeTA drops only the named TA`() {
        val course = taCourse("first@test.edu", "second@test.edu")

        val result = courseService.removeTA("CS-101", 2024, "Fall", 1, "first@test.edu")

        Assertions.assertTrue(result.startsWith("Removed"), result)
        Assertions.assertEquals(setOf("second@test.edu"), course.taEmails)
    }

    @Test
    fun `removeTA matches case-insensitively`() {
        val course = taCourse("First@Test.edu")

        courseService.removeTA("CS-101", 2024, "Fall", 1, "first@test.edu")

        Assertions.assertTrue(course.taEmails.isEmpty(), "stored casing must not keep a TA assigned")
    }

    @Test
    fun `removeTA reports an email that is not a TA`() {
        val course = taCourse("first@test.edu")

        val result = courseService.removeTA("CS-101", 2024, "Fall", 1, "nobody@test.edu")

        Assertions.assertTrue(result.contains("not assigned"), result)
        Assertions.assertEquals(1, course.taEmails.size)
    }

    // ==================== course.yml sync ====================

    private fun pastCourse(section: Int = 1, repo: String = "/repos/students") = Course(
        code = "CS-101", section = section, year = 2024, semester = "Fall",
        studentGitRepo = repo,
        endDate = LocalDateTime.of(2020, 12, 15, 0, 0)
    )

    @Test
    fun `adding a student requests a course-yml sync`() {
        val course = pastCourse()
        every { courseRepository.findByCodeAndYearAndSemesterAndSection("CS-101", 2024, "Fall", 1) } returns course

        courseService.addStudentToCourse("CS-101", 2024, "Fall", 1, "new@test.edu")

        verify(exactly = 1) { courseYamlSync.requestSync(course) }
    }

    @Test
    fun `a course that does not exist requests no sync`() {
        every { courseRepository.findByCodeAndYearAndSemesterAndSection(any(), any(), any(), any()) } returns null

        courseService.addStudentToCourse("CS-101", 2024, "Fall", 1, "new@test.edu")
        courseService.removeStudentFromCourse("CS-101", 2024, "Fall", 1, "new@test.edu")
        courseService.addTA("CS-101", 2024, "Fall", 1, "ta@test.edu")
        courseService.removeTA("CS-101", 2024, "Fall", 1, "ta@test.edu")

        verify(exactly = 0) { courseYamlSync.requestSync(any<Course>()) }
    }

    @Test
    fun `deleting a course requests a sync carrying the repo path it had before the delete`() {
        val course = pastCourse()
        every { courseRepository.findByCodeAndYearAndSemesterAndSection("CS-101", 2024, "Fall", 1) } returns course
        every { courseRepository.delete(any()) } just runs
        every { loginSessionRepository.deleteByCourseId(any()) } just runs

        courseService.removeCourse("CS-101", 2024, "Fall", "1")

        verify(exactly = 1) {
            courseYamlSync.requestSync(CourseYamlTarget("CS-101", 2024, "Fall", "/repos/students"))
        }
    }

    @Test
    fun `changeEndDate with section=all updates every section`() {
        val one = pastCourse(1)
        val two = pastCourse(2)
        every { courseRepository.findByCodeAndYearAndSemester("CS-101", 2024, "Fall") } returns listOf(one, two)

        val results = courseService.changeEndDate("CS-101", 2024, "Fall", "all", LocalDateTime.of(2024, 12, 20, 0, 0))

        Assertions.assertEquals(2, results.size)
        verify(exactly = 1) { courseYamlSync.requestSync(one) }
        verify(exactly = 1) { courseYamlSync.requestSync(two) }
    }

    @Test
    fun `applyCourseFile creates a missing section and updates an existing one`() {
        val existing = pastCourse(section = 2)
        every { courseRepository.findByCodeAndYearAndSemesterAndSection("CS-101", 2024, "Fall", 1) } returns null
        every { courseRepository.findByCodeAndYearAndSemesterAndSection("CS-101", 2024, "Fall", 2) } returns existing
        every { courseRepository.findById(existing.id) } returns java.util.Optional.of(existing)
        every { courseRepository.save(any()) } answers { firstArg() }

        val results = courseService.applyCourseFile(
            CourseInput(
                code = "CS-101", year = 2024, semester = "Fall",
                startDate = java.time.LocalDate.of(2024, 8, 19),
                endDate = java.time.LocalDate.of(2024, 12, 15),
                studentGitRepo = "/repos/students", problemGitRepo = "/repos/problems", language = "Java",
                sections = listOf(
                    SectionInput(number = 1, students = listOf("one@test.edu")),
                    SectionInput(number = 2, students = listOf("two@test.edu")),
                )
            )
        )

        Assertions.assertTrue(results[0].startsWith("Added course"))
        Assertions.assertTrue(results[1].startsWith("Updated course"))
    }

    @Test
    fun `applyCourseFile gives a problem with no language of its own the course default`() {
        every { courseRepository.findByCodeAndYearAndSemesterAndSection(any(), any(), any(), any()) } returns null
        val saved = slot<Course>()
        every { courseRepository.save(capture(saved)) } answers { firstArg() }

        courseService.applyCourseFile(
            CourseInput(
                code = "CS-101", year = 2024, semester = "Fall",
                startDate = java.time.LocalDate.of(2024, 8, 19),
                endDate = java.time.LocalDate.of(2024, 12, 15),
                language = "Java",
                sections = listOf(
                    SectionInput(
                        number = 1,
                        labs = listOf(
                            LabInput(
                                number = 1,
                                startDateTime = LocalDateTime.of(2024, 9, 2, 10, 0),
                                endDateTime = LocalDateTime.of(2024, 9, 2, 11, 15),
                                problems = listOf(ProblemInput(name = "plustwo"), ProblemInput(name = "quoted", language = "Python"))
                            )
                        )
                    )
                )
            )
        )

        val problems = saved.captured.labs.single().problems.associateBy { it.name }
        Assertions.assertEquals("Java", problems["plustwo"]!!.language)
        Assertions.assertEquals("Python", problems["quoted"]!!.language)
    }

    @Test
    fun `applyCourseFile lists the students and TAs an import removes, and nothing when none are`() {
        val existing = pastCourse()
        existing.students.addAll(listOf("kept@test.edu", "dropped@test.edu"))
        existing.taEmails.addAll(listOf("ta.kept@test.edu", "ta.dropped@test.edu"))
        every { courseRepository.findByCodeAndYearAndSemesterAndSection("CS-101", 2024, "Fall", 1) } returns existing
        every { courseRepository.findById(existing.id) } returns java.util.Optional.of(existing)
        fun file(students: List<String>, tas: List<String>) = CourseInput(
            code = "CS-101", year = 2024, semester = "Fall",
            startDate = java.time.LocalDate.of(2024, 8, 19),
            endDate = java.time.LocalDate.of(2024, 12, 15),
            sections = listOf(SectionInput(number = 1, tas = tas, students = students))
        )

        // A stale file: one student and one TA gone. The kept TA differs only in case and must not count as removed.
        val results = courseService.applyCourseFile(file(listOf("kept@test.edu"), listOf("TA.KEPT@test.edu")))

        Assertions.assertEquals(
            listOf("  Removed student dropped@test.edu", "  Removed TA ta.dropped@test.edu"),
            results.filter { it.contains("Removed") }
        )

        // Re-importing the same roster removes nobody.
        val again = courseService.applyCourseFile(file(listOf("kept@test.edu"), listOf("TA.KEPT@test.edu")))
        Assertions.assertTrue(again.none { it.contains("Removed") }, again.toString())
    }
}
