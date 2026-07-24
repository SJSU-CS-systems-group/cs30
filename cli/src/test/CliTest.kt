package cli

import com.cs30.cli.*
import com.cs30.server.models.Course
import com.cs30.server.models.Problem
import com.cs30.server.models.ScheduledLab
import com.cs30.server.repository.CourseRepository
import com.cs30.server.service.AppTimeZoneService
import com.cs30.server.service.CourseService
import com.cs30.server.service.GitService
import com.cs30.server.service.LabService
import io.mockk.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDateTime

class CliTest {

    private lateinit var courseService: CourseService
    private lateinit var courseRepository: CourseRepository
    private lateinit var gitService: GitService
    private lateinit var labService: LabService
    private lateinit var appTimeZoneService: AppTimeZoneService
    private lateinit var mockCli: CliOptions

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        courseService = mockk(relaxed = true)
        courseRepository = mockk(relaxed = true)
        gitService = mockk(relaxed = true)
        labService = mockk(relaxed = true)
        appTimeZoneService = AppTimeZoneService("America/Los_Angeles")
        mockCli = mockk<CliOptions>(relaxed = true)
        every { mockCli.out(any<String>()) } just runs
        every { mockCli.err(any<String>()) } just runs
    }

    // ====================================================================================
    // CourseCli Tests
    // ====================================================================================

    // ==================== AddCourse Tests ====================

    @Test
    fun `AddCourse should return 1 when file not found`() {
        val addCourse = AddCourse(courseService, courseRepository, gitService, appTimeZoneService)
        addCourse.filePath = "/nonexistent/path/course.yml"
        addCourse.cli = mockCli

        val result = addCourse.call()

        assertEquals(1, result)
        verify { mockCli.err("ERROR: File not found: /nonexistent/path/course.yml") }
    }

    @Test
    fun `AddCourse should return 1 when file is invalid YAML`() {
        val invalidFile = File(tempDir, "invalid.yml")
        invalidFile.writeText("this is not valid yaml: [")

        val addCourse = AddCourse(courseService, courseRepository, gitService, appTimeZoneService)
        addCourse.filePath = invalidFile.absolutePath
        addCourse.cli = mockCli

        val result = addCourse.call()

        assertEquals(1, result)
        verify { mockCli.err(match { it.startsWith("ERROR: Error parsing file:") }) }
    }

    @Test
    fun `AddCourse should return 0 and create course when valid YAML`() {
        val validYaml = """
            code: CS101
            year: 2024
            semester: Fall
            startDate: "2024-08-01"
            endDate: "2024-12-15"
            studentGitRepo: /tmp/students
            problemGitRepo: /tmp/problems
            language: Java
            sections:
              - number: 1
                labs: []
                students:
                  - student@test.edu
        """.trimIndent()
        val courseFile = File(tempDir, "course.yml")
        courseFile.writeText(validYaml)

        every { courseRepository.findByCodeAndYearAndSemesterAndSection("CS101", 2024, "Fall", 1) } returns null

        val addCourse = AddCourse(courseService, courseRepository, gitService, appTimeZoneService)
        addCourse.filePath = courseFile.absolutePath
        addCourse.cli = mockCli

        val result = addCourse.call()

        assertEquals(0, result)
        verify { gitService.initGitRepo("/tmp/students") }
        verify { gitService.initGitRepo("/tmp/problems") }
        verify { courseService.createCourseWithStudents(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `AddCourse should update existing course`() {
        val validYaml = """
            code: CS101
            year: 2024
            semester: Fall
            startDate: "2024-08-01"
            endDate: "2024-12-15"
            studentGitRepo: /tmp/students
            problemGitRepo: /tmp/problems
            language: Java
            sections:
              - number: 1
                labs: []
                students:
                  - student@test.edu
        """.trimIndent()
        val courseFile = File(tempDir, "course.yml")
        courseFile.writeText(validYaml)

        val existingCourse = mockk<Course>(relaxed = true)
        every { existingCourse.id } returns "course-1"
        every { courseRepository.findByCodeAndYearAndSemesterAndSection("CS101", 2024, "Fall", 1) } returns existingCourse

        val addCourse = AddCourse(courseService, courseRepository, gitService, appTimeZoneService)
        addCourse.filePath = courseFile.absolutePath
        addCourse.cli = mockCli

        val result = addCourse.call()

        assertEquals(0, result)
        verify { courseService.updateCourseWithStudents(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        verify { mockCli.out(match { it.contains("Updated course") }) }
    }

    // ==================== AddLab Tests ====================

    @Test
    fun `AddLab should return 1 when file not found`() {
        val addLab = AddLab(courseService, courseRepository, appTimeZoneService)
        addLab.filePath = "/nonexistent/path/lab.yml"
        addLab.cli = mockCli

        val result = addLab.call()

        assertEquals(1, result)
        verify { mockCli.err("ERROR: File not found: /nonexistent/path/lab.yml") }
    }

    @Test
    fun `AddLab should return 1 when file is invalid YAML`() {
        val invalidFile = File(tempDir, "invalid.yml")
        invalidFile.writeText("this is not valid yaml: [")

        val addLab = AddLab(courseService, courseRepository, appTimeZoneService)
        addLab.filePath = invalidFile.absolutePath
        addLab.cli = mockCli

        val result = addLab.call()

        assertEquals(1, result)
        verify { mockCli.err(match { it.startsWith("ERROR: Error parsing file:") }) }
    }

    @Test
    fun `AddLab should return 1 when course not found`() {
        val validYaml = """
            code: CS101
            year: 2024
            semester: Fall
            section: 1
            labNumber: 1
            startDateTime: "2024-09-02T10:00:00"
            endDateTime: "2024-09-02T11:15:00"
            problems:
              - name: "testproblem"
        """.trimIndent()
        val labFile = File(tempDir, "lab.yml")
        labFile.writeText(validYaml)

        every { courseRepository.findByCodeAndYearAndSemesterAndSection("CS101", 2024, "Fall", 1) } returns null

        val addLab = AddLab(courseService, courseRepository, appTimeZoneService)
        addLab.filePath = labFile.absolutePath
        addLab.cli = mockCli

        val result = addLab.call()

        assertEquals(1, result)
        verify { mockCli.err("ERROR: Course not found: CS101 (Section 1, Semester Fall, Year 2024)") }
    }

    @Test
    fun `AddLab should return 0 and add new lab when valid YAML`() {
        val validYaml = """
            code: CS101
            year: 2024
            semester: Fall
            section: 1
            labNumber: 1
            startDateTime: "2024-09-02T10:00:00"
            endDateTime: "2024-09-02T11:15:00"
            problems:
              - name: "testproblem"
                language: Python
              - name: "anotherproblem"
        """.trimIndent()
        val labFile = File(tempDir, "lab.yml")
        labFile.writeText(validYaml)

        val course = mockk<Course>(relaxed = true)
        every { course.language } returns "Java"
        every { courseRepository.findByCodeAndYearAndSemesterAndSection("CS101", 2024, "Fall", 1) } returns course
        every { courseService.addLab("CS101", 2024, "Fall", 1, any()) } returns "Added Lab 1 to CS101 (Section 1) with 2 problem(s)"

        val addLab = AddLab(courseService, courseRepository, appTimeZoneService)
        addLab.filePath = labFile.absolutePath
        addLab.cli = mockCli

        val result = addLab.call()

        assertEquals(0, result)
        verify { courseService.addLab("CS101", 2024, "Fall", 1, any()) }
        verify { mockCli.out("Added Lab 1 to CS101 (Section 1) with 2 problem(s)") }
    }

    @Test
    fun `AddLab should return 0 and update existing lab`() {
        val validYaml = """
            code: CS101
            year: 2024
            semester: Fall
            section: 1
            labNumber: 1
            startDateTime: "2024-09-02T10:00:00"
            endDateTime: "2024-09-02T11:15:00"
            problems:
              - name: "testproblem"
        """.trimIndent()
        val labFile = File(tempDir, "lab.yml")
        labFile.writeText(validYaml)

        val course = mockk<Course>(relaxed = true)
        every { course.language } returns "Java"
        every { courseRepository.findByCodeAndYearAndSemesterAndSection("CS101", 2024, "Fall", 1) } returns course
        every { courseService.addLab("CS101", 2024, "Fall", 1, any()) } returns "Updated Lab 1 in CS101 (Section 1) with 1 problem(s)"

        val addLab = AddLab(courseService, courseRepository, appTimeZoneService)
        addLab.filePath = labFile.absolutePath
        addLab.cli = mockCli

        val result = addLab.call()

        assertEquals(0, result)
        verify { mockCli.out("Updated Lab 1 in CS101 (Section 1) with 1 problem(s)") }
    }

    @Test
    fun `AddLab should use course default language when problem language not specified`() {
        val validYaml = """
            code: CS101
            year: 2024
            semester: Fall
            section: 1
            labNumber: 1
            startDateTime: "2024-09-02T10:00:00"
            endDateTime: "2024-09-02T11:15:00"
            problems:
              - name: "testproblem"
        """.trimIndent()
        val labFile = File(tempDir, "lab.yml")
        labFile.writeText(validYaml)

        val course = mockk<Course>(relaxed = true)
        every { course.language } returns "Python"
        every { courseRepository.findByCodeAndYearAndSemesterAndSection("CS101", 2024, "Fall", 1) } returns course

        val capturedLab = slot<ScheduledLab>()
        every { courseService.addLab("CS101", 2024, "Fall", 1, capture(capturedLab)) } returns "Added Lab 1"

        val addLab = AddLab(courseService, courseRepository, appTimeZoneService)
        addLab.filePath = labFile.absolutePath
        addLab.cli = mockCli

        addLab.call()

        assertEquals("Python", capturedLab.captured.problems.first().language)
    }

    @Test
    fun `AddLab should return 1 when courseService returns error`() {
        val validYaml = """
            code: CS101
            year: 2024
            semester: Fall
            section: 1
            labNumber: 1
            startDateTime: "2024-09-02T10:00:00"
            endDateTime: "2024-09-02T11:15:00"
            problems: []
        """.trimIndent()
        val labFile = File(tempDir, "lab.yml")
        labFile.writeText(validYaml)

        val course = mockk<Course>(relaxed = true)
        every { course.language } returns "Java"
        every { courseRepository.findByCodeAndYearAndSemesterAndSection("CS101", 2024, "Fall", 1) } returns course
        every { courseService.addLab("CS101", 2024, "Fall", 1, any()) } returns "ERROR: Something went wrong"

        val addLab = AddLab(courseService, courseRepository, appTimeZoneService)
        addLab.filePath = labFile.absolutePath
        addLab.cli = mockCli

        val result = addLab.call()

        assertEquals(1, result)
        verify { mockCli.err("ERROR: Something went wrong") }
    }

    // ==================== AddStudent Tests ====================

    @Test
    fun `AddStudent should return 0 on success`() {
        every { courseService.addStudentToCourse("CS-101", 2024, "Fall", 1, "student@test.edu") } returns
            "Added student student@test.edu to course CS-101 (Section 1)"

        val addStudent = AddStudent(courseService)
        addStudent.code = "CS-101"
        addStudent.year = 2024
        addStudent.semester = "Fall"
        addStudent.section = 1
        addStudent.email = "student@test.edu"
        addStudent.cli = mockCli

        val result = addStudent.call()

        assertEquals(0, result)
        verify { mockCli.out("Added student student@test.edu to course CS-101 (Section 1)") }
    }

    @Test
    fun `AddStudent should return 1 on failure`() {
        every { courseService.addStudentToCourse("CS-101", 2024, "Fall", 1, "student@test.edu") } returns
            "Course not found: CS-101 (Section 1)"

        val addStudent = AddStudent(courseService)
        addStudent.code = "CS-101"
        addStudent.year = 2024
        addStudent.semester = "Fall"
        addStudent.section = 1
        addStudent.email = "student@test.edu"
        addStudent.cli = mockCli

        val result = addStudent.call()

        assertEquals(1, result)
        verify { mockCli.err("Course not found: CS-101 (Section 1)") }
    }

    // ==================== ChangeEndDate Tests ====================

    @Test
    fun `ChangeEndDate should return 1 for invalid date format`() {
        val changeEndDate = ChangeEndDate(courseRepository, appTimeZoneService, courseService)
        changeEndDate.code = "CS-101"
        changeEndDate.year = 2024
        changeEndDate.semester = "Fall"
        changeEndDate.section = "1"
        changeEndDate.endDate = "invalid-date"
        changeEndDate.cli = mockCli

        val result = changeEndDate.call()

        assertEquals(1, result)
        verify { mockCli.err("ERROR: Invalid date format: invalid-date (expected yyyy-MM-dd)") }
    }

    @Test
    fun `ChangeEndDate should return 1 when course not found`() {
        every { courseRepository.findByCodeAndYearAndSemesterAndSection("CS-101", 2024, "Fall", 1) } returns null

        val changeEndDate = ChangeEndDate(courseRepository, appTimeZoneService, courseService)
        changeEndDate.code = "CS-101"
        changeEndDate.year = 2024
        changeEndDate.semester = "Fall"
        changeEndDate.section = "1"
        changeEndDate.endDate = "2024-12-31"
        changeEndDate.cli = mockCli

        val result = changeEndDate.call()

        assertEquals(1, result)
        verify { mockCli.err("ERROR: Course not found: CS-101 (Section 1)") }
    }

    @Test
    fun `ChangeEndDate should return 0 and update course`() {
        val course = Course(
            id = "course-1",
            code = "CS-101",
            section = 1,
            year = 2024,
            semester = "Fall",
            startDate = LocalDateTime.of(2024, 8, 1, 0, 0),
            endDate = LocalDateTime.of(2024, 12, 1, 0, 0)
        )
        every { courseRepository.findByCodeAndYearAndSemesterAndSection("CS-101", 2024, "Fall", 1) } returns course
        every { courseRepository.save(any<Course>()) } answers { firstArg() }

        val changeEndDate = ChangeEndDate(courseRepository, appTimeZoneService, courseService)
        changeEndDate.code = "CS-101"
        changeEndDate.year = 2024
        changeEndDate.semester = "Fall"
        changeEndDate.section = "1"
        changeEndDate.endDate = "2024-12-31"
        changeEndDate.cli = mockCli

        val result = changeEndDate.call()

        assertEquals(0, result)
        verify { courseRepository.save(any<Course>()) }
        verify { mockCli.out(match { it.contains("Updated end date") }) }
    }

    @Test
    fun `ChangeEndDate should update all sections when section is all`() {
        val course1 = Course(
            id = "course-1",
            code = "CS-101",
            section = 1,
            year = 2024,
            semester = "Fall",
            startDate = LocalDateTime.of(2024, 8, 1, 0, 0),
            endDate = LocalDateTime.of(2024, 12, 1, 0, 0)
        )
        val course2 = Course(
            id = "course-2",
            code = "CS-101",
            section = 2,
            year = 2024,
            semester = "Fall",
            startDate = LocalDateTime.of(2024, 8, 1, 0, 0),
            endDate = LocalDateTime.of(2024, 12, 1, 0, 0)
        )
        every { courseRepository.findByCodeAndYearAndSemester("CS-101", 2024, "Fall") } returns listOf(course1, course2)
        every { courseRepository.save(any<Course>()) } answers { firstArg() }

        val changeEndDate = ChangeEndDate(courseRepository, appTimeZoneService, courseService)
        changeEndDate.code = "CS-101"
        changeEndDate.year = 2024
        changeEndDate.semester = "Fall"
        changeEndDate.section = "all"
        changeEndDate.endDate = "2024-12-31"
        changeEndDate.cli = mockCli

        val result = changeEndDate.call()

        assertEquals(0, result)
        verify(exactly = 2) { courseRepository.save(any<Course>()) }
    }

    // ==================== RemoveCourse Tests ====================

    @Test
    fun `RemoveCourse should return 0 when course deleted`() {
        every { courseService.removeCourse("CS-101", 2024, "Fall", "1") } returns
            listOf("Deleted course CS-101 (Section 1)")

        val removeCourse = RemoveCourse(courseService)
        removeCourse.code = "CS-101"
        removeCourse.year = 2024
        removeCourse.semester = "Fall"
        removeCourse.section = "1"
        removeCourse.cli = mockCli

        val result = removeCourse.call()

        assertEquals(0, result)
        verify { mockCli.out("Deleted course CS-101 (Section 1)") }
    }

    @Test
    fun `RemoveCourse should return 1 when course not ended yet`() {
        every { courseService.removeCourse("CS-101", 2024, "Fall", "1") } returns
            listOf("Cannot delete course CS-101 (Section 1) because it has not ended yet")

        val removeCourse = RemoveCourse(courseService)
        removeCourse.code = "CS-101"
        removeCourse.year = 2024
        removeCourse.semester = "Fall"
        removeCourse.section = "1"
        removeCourse.cli = mockCli

        val result = removeCourse.call()

        assertEquals(1, result)
        verify { mockCli.err("Cannot delete course CS-101 (Section 1) because it has not ended yet") }
    }

    // ==================== RemoveStudent Tests ====================

    @Test
    fun `RemoveStudent should return 0 on success`() {
        every { courseService.removeStudentFromCourse("CS-101", 2024, "Fall", 1, "student@test.edu") } returns
            "Removed student student@test.edu from course CS-101 (Section 1)"

        val removeStudent = RemoveStudent(courseService)
        removeStudent.code = "CS-101"
        removeStudent.year = 2024
        removeStudent.semester = "Fall"
        removeStudent.section = 1
        removeStudent.email = "student@test.edu"
        removeStudent.cli = mockCli

        val result = removeStudent.call()

        assertEquals(0, result)
        verify { mockCli.out("Removed student student@test.edu from course CS-101 (Section 1)") }
    }

    @Test
    fun `RemoveStudent should return 1 when student not found`() {
        every { courseService.removeStudentFromCourse("CS-101", 2024, "Fall", 1, "student@test.edu") } returns
            "Student student@test.edu is not enrolled in CS-101 (Section 1)"

        val removeStudent = RemoveStudent(courseService)
        removeStudent.code = "CS-101"
        removeStudent.year = 2024
        removeStudent.semester = "Fall"
        removeStudent.section = 1
        removeStudent.email = "student@test.edu"
        removeStudent.cli = mockCli

        val result = removeStudent.call()

        assertEquals(1, result)
        verify { mockCli.err("Student student@test.edu is not enrolled in CS-101 (Section 1)") }
    }

    // ==================== FindCourse Tests ====================

    @Test
    fun `FindCourse should return 0 when course found`() {
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
        findCourse.cli = mockCli

        val result = findCourse.call()

        assertEquals(0, result)
        verify { mockCli.out("Course: CS-101 (Section 1)") }
    }

    @Test
    fun `FindCourse should return 1 when course not found`() {
        every { courseService.findCourse("CS-999", 2024, "Fall", "1") } returns
            listOf("ERROR: Course not found: CS-999 (Section 1)")

        val findCourse = FindCourse(courseService)
        findCourse.code = "CS-999"
        findCourse.year = 2024
        findCourse.semester = "Fall"
        findCourse.section = "1"
        findCourse.cli = mockCli

        val result = findCourse.call()

        assertEquals(1, result)
        verify { mockCli.err("ERROR: Course not found: CS-999 (Section 1)") }
    }

    // ==================== FindStudent Tests ====================

    @Test
    fun `FindStudent should return 0 when student found in courses`() {
        every { courseService.findStudent("student@test.edu") } returns
            listOf(
                "Student: student@test.edu",
                "Enrolled in 2 course(s):",
                "  - CS-101 (Section 1)",
                "  - CS-102 (Section 1)"
            )

        val findStudent = FindStudent(courseService)
        findStudent.email = "student@test.edu"
        findStudent.cli = mockCli

        val result = findStudent.call()

        assertEquals(0, result)
        verify { mockCli.out("Student: student@test.edu") }
    }

    @Test
    fun `FindStudent should return 1 when student not found`() {
        every { courseService.findStudent("unknown@test.edu") } returns
            listOf("ERROR: No courses found for student: unknown@test.edu")

        val findStudent = FindStudent(courseService)
        findStudent.email = "unknown@test.edu"
        findStudent.cli = mockCli

        val result = findStudent.call()

        assertEquals(1, result)
        verify { mockCli.err("ERROR: No courses found for student: unknown@test.edu") }
    }

    // ====================================================================================
    // ProblemCli Tests
    // ====================================================================================

    // ==================== AddProblem Tests ====================

    @Test
    fun `AddProblem should return 1 when problem directory not found`() {
        val addProblem = AddProblem(gitService)
        addProblem.problemDir = "/nonexistent/path"
        addProblem.problemGitRepo = "/tmp/problems"
        addProblem.cli = mockCli

        val result = addProblem.call()

        assertEquals(1, result)
        verify { mockCli.err("ERROR: Problem directory not found or is not a directory: /nonexistent/path") }
    }

    @Test
    fun `AddProblem should return 0 on success`() {
        val problemDir = File(tempDir, "testproblem")
        problemDir.mkdirs()

        val addProblem = AddProblem(gitService)
        addProblem.problemDir = problemDir.absolutePath
        addProblem.problemGitRepo = "/tmp/problems"
        addProblem.cli = mockCli

        val result = addProblem.call()

        assertEquals(0, result)
        verify { gitService.addProblemToRepo("/tmp/problems", problemDir.absolutePath) }
        verify { mockCli.out("Problem added successfully!") }
    }

    @Test
    fun `AddProblem should return 1 when git service throws exception`() {
        val problemDir = File(tempDir, "testproblem")
        problemDir.mkdirs()

        every { gitService.addProblemToRepo(any(), any()) } throws RuntimeException("Git error")

        val addProblem = AddProblem(gitService)
        addProblem.problemDir = problemDir.absolutePath
        addProblem.problemGitRepo = "/tmp/problems"
        addProblem.cli = mockCli

        val result = addProblem.call()

        assertEquals(1, result)
        verify { mockCli.err("ERROR: Git error") }
    }

    // ==================== AddProblems Tests ====================

    @Test
    fun `AddProblems should return 1 when directory not found`() {
        val addProblems = AddProblems(gitService)
        addProblems.problemsDir = "/nonexistent/path"
        addProblems.problemGitRepo = "/tmp/problems"
        addProblems.cli = mockCli

        val result = addProblems.call()

        assertEquals(1, result)
        verify { mockCli.err("ERROR: Problems directory not found or is not a directory: /nonexistent/path") }
    }

    @Test
    fun `AddProblems should return 1 when git repo is blank`() {
        val problemsDir = File(tempDir, "problems")
        problemsDir.mkdirs()

        val addProblems = AddProblems(gitService)
        addProblems.problemsDir = problemsDir.absolutePath
        addProblems.problemGitRepo = ""
        addProblems.cli = mockCli

        val result = addProblems.call()

        assertEquals(1, result)
        verify { mockCli.err("ERROR: Git repository path is required") }
    }

    @Test
    fun `AddProblems should return 0 on success`() {
        val problemsDir = File(tempDir, "problems")
        problemsDir.mkdirs()

        val addProblems = AddProblems(gitService)
        addProblems.problemsDir = problemsDir.absolutePath
        addProblems.problemGitRepo = "/tmp/problems"
        addProblems.cli = mockCli

        val result = addProblems.call()

        assertEquals(0, result)
        verify { gitService.initGitRepo("/tmp/problems") }
        verify { gitService.addProblemsToRepo("/tmp/problems", problemsDir.absolutePath) }
        verify { mockCli.out("All problems added successfully!") }
    }

    // ==================== RemoveProblem Tests ====================

    @Test
    fun `RemoveProblem should return 1 when git repo is blank`() {
        val removeProblem = RemoveProblem(gitService)
        removeProblem.problemGitRepo = ""
        removeProblem.problemName = "testproblem"
        removeProblem.cli = mockCli

        val result = removeProblem.call()

        assertEquals(1, result)
        verify { mockCli.err("ERROR: Git repository path is required") }
    }

    @Test
    fun `RemoveProblem should return 0 on success`() {
        val removeProblem = RemoveProblem(gitService)
        removeProblem.problemGitRepo = "/tmp/problems"
        removeProblem.problemName = "testproblem"
        removeProblem.cli = mockCli

        val result = removeProblem.call()

        assertEquals(0, result)
        verify { gitService.removeProblemFromRepo("/tmp/problems", "testproblem") }
        verify { mockCli.out("Problem removed successfully!") }
    }

    @Test
    fun `RemoveProblem should return 1 when git service throws exception`() {
        every { gitService.removeProblemFromRepo(any(), any()) } throws RuntimeException("Problem not found")

        val removeProblem = RemoveProblem(gitService)
        removeProblem.problemGitRepo = "/tmp/problems"
        removeProblem.problemName = "nonexistent"
        removeProblem.cli = mockCli

        val result = removeProblem.call()

        assertEquals(1, result)
        verify { mockCli.err("ERROR: Problem not found") }
    }

    // ==================== UpdateProblemLanguage Tests ====================

    @Test
    fun `UpdateProblemLanguage should return 1 when course not found`() {
        every { courseRepository.findByCodeAndYearAndSemesterAndSection("CS-101", 2024, "Fall", 1) } returns null

        val updateProblemLanguage = UpdateProblemLanguage(courseRepository, labService, courseService)
        updateProblemLanguage.courseCode = "CS-101"
        updateProblemLanguage.year = 2024
        updateProblemLanguage.semester = "Fall"
        updateProblemLanguage.section = 1
        updateProblemLanguage.labNumber = 1
        updateProblemLanguage.problemName = "testproblem"
        updateProblemLanguage.language = "python"
        updateProblemLanguage.cli = mockCli

        val result = updateProblemLanguage.call()

        assertEquals(1, result)
        verify { mockCli.err("ERROR: Course not found: CS-101 2024 Fall Section 1") }
    }

    @Test
    fun `UpdateProblemLanguage should return 0 on success`() {
        val course = mockk<Course>(relaxed = true)
        every { courseRepository.findByCodeAndYearAndSemesterAndSection("CS-101", 2024, "Fall", 1) } returns course
        every { labService.updateProblemLanguage(course, 1, "testproblem", "python") } returns "Updated language to python"

        val updateProblemLanguage = UpdateProblemLanguage(courseRepository, labService, courseService)
        updateProblemLanguage.courseCode = "CS-101"
        updateProblemLanguage.year = 2024
        updateProblemLanguage.semester = "Fall"
        updateProblemLanguage.section = 1
        updateProblemLanguage.labNumber = 1
        updateProblemLanguage.problemName = "testproblem"
        updateProblemLanguage.language = "python"
        updateProblemLanguage.cli = mockCli

        val result = updateProblemLanguage.call()

        assertEquals(0, result)
        verify { mockCli.out("Updated language to python") }
    }

    // ==================== CancelLab Tests ====================

    @Test
    fun `CancelLab should return 1 when course not found`() {
        every { courseRepository.findByCodeAndYearAndSemesterAndSection("CS-101", 2024, "Fall", 1) } returns null

        val cancelLab = CancelLab(courseRepository, labService, courseService)
        cancelLab.courseCode = "CS-101"
        cancelLab.year = 2024
        cancelLab.semester = "Fall"
        cancelLab.section = 1
        cancelLab.labNumber = 1
        cancelLab.cli = mockCli

        val result = cancelLab.call()

        assertEquals(1, result)
        verify { mockCli.err("ERROR: Course not found: CS-101 2024 Fall Section 1") }
    }

    @Test
    fun `CancelLab should return 0 on success`() {
        val course = mockk<Course>(relaxed = true)
        every { courseRepository.findByCodeAndYearAndSemesterAndSection("CS-101", 2024, "Fall", 1) } returns course
        every { labService.cancelLab(course, 1) } returns listOf("Removed problem: testproblem", "Lab 1 cancelled")

        val cancelLab = CancelLab(courseRepository, labService, courseService)
        cancelLab.courseCode = "CS-101"
        cancelLab.year = 2024
        cancelLab.semester = "Fall"
        cancelLab.section = 1
        cancelLab.labNumber = 1
        cancelLab.cli = mockCli

        val result = cancelLab.call()

        assertEquals(0, result)
        verify { mockCli.out("Lab cancelled successfully!") }
    }

    @Test
    fun `CancelLab should return 1 when lab service returns error`() {
        val course = mockk<Course>(relaxed = true)
        every { courseRepository.findByCodeAndYearAndSemesterAndSection("CS-101", 2024, "Fall", 1) } returns course
        every { labService.cancelLab(course, 1) } returns listOf("ERROR: Lab not found")

        val cancelLab = CancelLab(courseRepository, labService, courseService)
        cancelLab.courseCode = "CS-101"
        cancelLab.year = 2024
        cancelLab.semester = "Fall"
        cancelLab.section = 1
        cancelLab.labNumber = 1
        cancelLab.cli = mockCli

        val result = cancelLab.call()

        assertEquals(1, result)
    }

    // ==================== ValidateCourse Tests ====================

    @Test
    fun `ValidateCourse should return 1 when course not found`() {
        every { courseRepository.findByCodeAndYearAndSemesterAndSection("CS-101", 2024, "Fall", 1) } returns null

        val validateCourse = ValidateCourse(courseRepository, gitService, courseService)
        validateCourse.courseCode = "CS-101"
        validateCourse.year = 2024
        validateCourse.semester = "Fall"
        validateCourse.section = "1"
        validateCourse.cli = mockCli

        val result = validateCourse.call()

        assertEquals(1, result)
        verify { mockCli.err("ERROR: Course not found: CS-101 2024 Fall Section 1") }
    }

    @Test
    fun `ValidateCourse should return 1 when problem git repo is blank`() {
        val course = mockk<Course>(relaxed = true)
        every { course.problemGitRepo } returns ""
        every { courseRepository.findByCodeAndYearAndSemesterAndSection("CS-101", 2024, "Fall", 1) } returns course

        val validateCourse = ValidateCourse(courseRepository, gitService, courseService)
        validateCourse.courseCode = "CS-101"
        validateCourse.year = 2024
        validateCourse.semester = "Fall"
        validateCourse.section = "1"
        validateCourse.cli = mockCli

        val result = validateCourse.call()

        assertEquals(1, result)
        verify { mockCli.err("ERROR: Course does not have a problem git repository configured") }
    }

    @Test
    fun `ValidateCourse should return 0 when no problems in course`() {
        val course = mockk<Course>(relaxed = true)
        every { course.problemGitRepo } returns "/tmp/problems"
        every { course.labs } returns mutableListOf()
        every { courseRepository.findByCodeAndYearAndSemesterAndSection("CS-101", 2024, "Fall", 1) } returns course

        val validateCourse = ValidateCourse(courseRepository, gitService, courseService)
        validateCourse.courseCode = "CS-101"
        validateCourse.year = 2024
        validateCourse.semester = "Fall"
        validateCourse.section = "1"
        validateCourse.cli = mockCli

        val result = validateCourse.call()

        assertEquals(0, result)
        verify { mockCli.out("No problems found in course.") }
    }

    @Test
    fun `ValidateCourse should return 0 when all problems exist`() {
        val problem1 = mockk<Problem>(relaxed = true)
        every { problem1.name } returns "problem1"
        val problem2 = mockk<Problem>(relaxed = true)
        every { problem2.name } returns "problem2"
        val lab = mockk<ScheduledLab>(relaxed = true)
        every { lab.problems } returns mutableListOf(problem1, problem2)
        val course = mockk<Course>(relaxed = true)
        every { course.problemGitRepo } returns "/tmp/problems"
        every { course.labs } returns mutableListOf(lab)
        every { courseRepository.findByCodeAndYearAndSemesterAndSection("CS-101", 2024, "Fall", 1) } returns course
        every { gitService.problemExistsInRepo("/tmp/problems", "problem1") } returns true
        every { gitService.problemExistsInRepo("/tmp/problems", "problem2") } returns true

        val validateCourse = ValidateCourse(courseRepository, gitService, courseService)
        validateCourse.courseCode = "CS-101"
        validateCourse.year = 2024
        validateCourse.semester = "Fall"
        validateCourse.section = "1"
        validateCourse.cli = mockCli

        val result = validateCourse.call()

        assertEquals(0, result)
        verify { mockCli.out("All problems exist in the git repo.") }
    }

    @Test
    fun `ValidateCourse should return 1 when some problems are missing`() {
        val problem1 = mockk<Problem>(relaxed = true)
        every { problem1.name } returns "problem1"
        val problem2 = mockk<Problem>(relaxed = true)
        every { problem2.name } returns "missing_problem"
        val lab = mockk<ScheduledLab>(relaxed = true)
        every { lab.problems } returns mutableListOf(problem1, problem2)
        val course = mockk<Course>(relaxed = true)
        every { course.problemGitRepo } returns "/tmp/problems"
        every { course.labs } returns mutableListOf(lab)
        every { courseRepository.findByCodeAndYearAndSemesterAndSection("CS-101", 2024, "Fall", 1) } returns course
        every { gitService.problemExistsInRepo("/tmp/problems", "problem1") } returns true
        every { gitService.problemExistsInRepo("/tmp/problems", "missing_problem") } returns false

        val validateCourse = ValidateCourse(courseRepository, gitService, courseService)
        validateCourse.courseCode = "CS-101"
        validateCourse.year = 2024
        validateCourse.semester = "Fall"
        validateCourse.section = "1"
        validateCourse.cli = mockCli

        val result = validateCourse.call()

        assertEquals(1, result)
        verify { mockCli.err(match { it.contains("Missing problems") }) }
    }

    @Test
    fun `ValidateCourse should validate all sections when section is all`() {
        val problem = mockk<Problem>(relaxed = true)
        every { problem.name } returns "problem1"
        val lab = mockk<ScheduledLab>(relaxed = true)
        every { lab.problems } returns mutableListOf(problem)
        val course1 = mockk<Course>(relaxed = true)
        every { course1.problemGitRepo } returns "/tmp/problems"
        every { course1.labs } returns mutableListOf(lab)
        val course2 = mockk<Course>(relaxed = true)
        every { course2.problemGitRepo } returns "/tmp/problems"
        every { course2.labs } returns mutableListOf(lab)
        every { courseRepository.findByCodeAndYearAndSemester("CS-101", 2024, "Fall") } returns listOf(course1, course2)
        every { gitService.problemExistsInRepo("/tmp/problems", "problem1") } returns true

        val validateCourse = ValidateCourse(courseRepository, gitService, courseService)
        validateCourse.courseCode = "CS-101"
        validateCourse.year = 2024
        validateCourse.semester = "Fall"
        validateCourse.section = "all"
        validateCourse.cli = mockCli

        val result = validateCourse.call()

        assertEquals(0, result)
    }
}