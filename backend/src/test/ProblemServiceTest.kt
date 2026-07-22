import com.cs30.server.models.Course
import com.cs30.server.models.Problem
import com.cs30.server.models.ScheduledLab
import com.cs30.server.repository.CourseRepository
import com.cs30.server.service.ProblemService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDateTime
import java.util.Optional

class ProblemServiceTest {

    private lateinit var courseRepository: CourseRepository
    private lateinit var problemService: ProblemService

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        courseRepository = mockk(relaxed = true)
        problemService = ProblemService(courseRepository)
        // Enrollment is checked via the repo (existsByIdAndStudentsContaining), not course.students —
        // the enrolled student passes; "unenrolled@sjsu.edu" falls through to the relaxed default (false).
        every { courseRepository.existsByIdAndStudentsContaining(any(), "student@sjsu.edu") } returns true
    }

    private fun createActiveCourse(problemGitRepo: String = ""): Course {
        val course = Course(
            id = "course-1",
            code = "CS-101",
            section = 1,
            year = 2024,
            semester = "Fall",
            language = "Java",
            problemGitRepo = problemGitRepo
        )
        course.students.add("student@sjsu.edu")

        val lab = ScheduledLab(
            labNumber = 1,
            startDateTime = LocalDateTime.now().minusHours(1),
            endDateTime = LocalDateTime.now().plusHours(1)
        )
        lab.addProblem(Problem(name = "hello-world", language = "Java"))
        lab.addProblem(Problem(name = "fizz-buzz", language = "Python"))
        course.addLab(lab)

        return course
    }

    private fun createInactiveCourse(): Course {
        val course = Course(
            id = "course-2",
            code = "CS-102",
            section = 1,
            year = 2024,
            semester = "Fall",
            language = "Java"
        )
        course.students.add("student@sjsu.edu")

        // Lab that hasn't started yet
        val lab = ScheduledLab(
            labNumber = 1,
            startDateTime = LocalDateTime.now().plusHours(1),
            endDateTime = LocalDateTime.now().plusHours(2)
        )
        lab.addProblem(Problem(name = "future-problem", language = "Java"))
        course.addLab(lab)

        return course
    }

    // ==================== listProblemsForStudent tests ====================

    @Test
    fun `listProblemsForStudent should return empty list when no courses found`() {
        every { courseRepository.findByStudentEmail("unknown@sjsu.edu") } returns emptyList()

        val result = problemService.listProblemsForStudent("unknown@sjsu.edu")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `listProblemsForStudent should return problems from active labs only`() {
        val activeCourse = createActiveCourse()
        val inactiveCourse = createInactiveCourse()
        every { courseRepository.findByStudentEmail("student@sjsu.edu") } returns listOf(activeCourse, inactiveCourse)

        val result = problemService.listProblemsForStudent("student@sjsu.edu")

        assertEquals(2, result.size) // Only from active lab
        assertTrue(result.any { it.slug == "hello-world" })
        assertTrue(result.any { it.slug == "fizz-buzz" })
        assertFalse(result.any { it.slug == "future-problem" })
    }

    @Test
    fun `listProblemsForStudent should return problems sorted by section, lab, title`() {
        val course1 = Course(id = "c1", code = "CS-101", section = 1, language = "Java")
        course1.students.add("student@sjsu.edu")
        val lab1 = ScheduledLab(
            labNumber = 2,
            startDateTime = LocalDateTime.now().minusHours(1),
            endDateTime = LocalDateTime.now().plusHours(1)
        )
        lab1.addProblem(Problem(name = "zebra", language = "Java"))
        course1.addLab(lab1)

        val course2 = Course(id = "c2", code = "CS-101", section = 2, language = "Java")
        course2.students.add("student@sjsu.edu")
        val lab2 = ScheduledLab(
            labNumber = 1,
            startDateTime = LocalDateTime.now().minusHours(1),
            endDateTime = LocalDateTime.now().plusHours(1)
        )
        lab2.addProblem(Problem(name = "apple", language = "Java"))
        course2.addLab(lab2)

        every { courseRepository.findByStudentEmail("student@sjsu.edu") } returns listOf(course1, course2)

        val result = problemService.listProblemsForStudent("student@sjsu.edu")

        assertEquals(2, result.size)
        assertEquals(1, result[0].section) // Section 1 first
        assertEquals(2, result[1].section) // Section 2 second
    }

    @Test
    fun `listProblemsForStudent should use problem language or fall back to course language`() {
        val course = createActiveCourse()
        // One problem has explicit language, one is blank
        course.labs[0].problems[0].language = "Python"
        course.labs[0].problems[1].language = ""
        every { courseRepository.findByStudentEmail("student@sjsu.edu") } returns listOf(course)

        val result = problemService.listProblemsForStudent("student@sjsu.edu")

        val pythonProblem = result.find { it.slug == "hello-world" }
        val javaProblem = result.find { it.slug == "fizz-buzz" }
        assertEquals("Python", pythonProblem?.language)
        assertEquals("Java", javaProblem?.language) // Falls back to course language
    }

    // ==================== getProblemContent tests ====================

    @Test
    fun `getProblemContent should return null when course not found`() {
        every { courseRepository.findById("invalid") } returns Optional.empty()

        val result = problemService.getProblemContent("student@sjsu.edu", "invalid", 1, 1, "problem")

        assertNull(result)
    }

    @Test
    fun `getProblemContent should return null when student not enrolled`() {
        val course = createActiveCourse()
        every { courseRepository.findById("course-1") } returns Optional.of(course)

        val result = problemService.getProblemContent("unenrolled@sjsu.edu", "course-1", 1, 1, "hello-world")

        assertNull(result)
    }

    @Test
    fun `getProblemContent should return null when section mismatch`() {
        val course = createActiveCourse()
        every { courseRepository.findById("course-1") } returns Optional.of(course)

        val result = problemService.getProblemContent("student@sjsu.edu", "course-1", 99, 1, "hello-world")

        assertNull(result)
    }

    @Test
    fun `getProblemContent should return null when problem not in lab`() {
        val course = createActiveCourse()
        every { courseRepository.findById("course-1") } returns Optional.of(course)

        val result = problemService.getProblemContent("student@sjsu.edu", "course-1", 1, 1, "nonexistent")

        assertNull(result)
    }

    @Test
    fun `getProblemContent should return null when lab is inactive`() {
        val course = createInactiveCourse()
        every { courseRepository.findById("course-2") } returns Optional.of(course)

        val result = problemService.getProblemContent("student@sjsu.edu", "course-2", 1, 1, "future-problem")

        assertNull(result)
    }

    @Test
    fun `getProblemContent should return null when problem repo not configured`() {
        val course = createActiveCourse(problemGitRepo = "")
        every { courseRepository.findById("course-1") } returns Optional.of(course)

        val result = problemService.getProblemContent("student@sjsu.edu", "course-1", 1, 1, "hello-world")

        assertNull(result)
    }

    @Test
    fun `getProblemContent should return html and css when files exist`() {
        // Create temp problem directory with files
        val problemDir = File(tempDir, "hello-world")
        problemDir.mkdirs()
        File(problemDir, "index.html").writeText("<h1>Hello World</h1>")
        File(problemDir, "problem.css").writeText("h1 { color: red; }")

        val course = createActiveCourse(problemGitRepo = tempDir.absolutePath)
        every { courseRepository.findById("course-1") } returns Optional.of(course)

        val result = problemService.getProblemContent("student@sjsu.edu", "course-1", 1, 1, "hello-world")

        assertNotNull(result)
        assertTrue(result!!.html.contains("Hello World"))
        assertTrue(result.css.contains("color: red"))
    }

    @Test
    fun `getProblemContent should return empty css when css file missing`() {
        val problemDir = File(tempDir, "hello-world")
        problemDir.mkdirs()
        File(problemDir, "index.html").writeText("<h1>Hello</h1>")
        // No CSS file

        val course = createActiveCourse(problemGitRepo = tempDir.absolutePath)
        every { courseRepository.findById("course-1") } returns Optional.of(course)

        val result = problemService.getProblemContent("student@sjsu.edu", "course-1", 1, 1, "hello-world")

        assertNotNull(result)
        assertEquals("", result!!.css)
    }

    @Test
    fun `getProblemContent should rewrite relative image paths`() {
        val problemDir = File(tempDir, "hello-world")
        problemDir.mkdirs()
        File(problemDir, "index.html").writeText("""<img src="images/diagram.png">""")

        val course = createActiveCourse(problemGitRepo = tempDir.absolutePath)
        every { courseRepository.findById("course-1") } returns Optional.of(course)

        val result = problemService.getProblemContent("student@sjsu.edu", "course-1", 1, 1, "hello-world")

        assertNotNull(result)
        assertTrue(result!!.html.contains("/api/problems/course-1/section/1/lab/1/hello-world/assets/images/diagram.png"))
    }

    @Test
    fun `getProblemContent should not rewrite absolute URLs`() {
        val problemDir = File(tempDir, "hello-world")
        problemDir.mkdirs()
        File(problemDir, "index.html").writeText("""<img src="https://example.com/image.png">""")

        val course = createActiveCourse(problemGitRepo = tempDir.absolutePath)
        every { courseRepository.findById("course-1") } returns Optional.of(course)

        val result = problemService.getProblemContent("student@sjsu.edu", "course-1", 1, 1, "hello-world")

        assertNotNull(result)
        assertTrue(result!!.html.contains("https://example.com/image.png"))
        assertFalse(result.html.contains("/api/problems/"))
    }

    // ==================== getProblemAssetFile tests ====================

    @Test
    fun `getProblemAssetFile should return null for path traversal attempt`() {
        val problemDir = File(tempDir, "hello-world")
        problemDir.mkdirs()

        val course = createActiveCourse(problemGitRepo = tempDir.absolutePath)
        every { courseRepository.findById("course-1") } returns Optional.of(course)

        val result = problemService.getProblemAssetFile(
            "student@sjsu.edu", "course-1", 1, 1, "hello-world", "../../../etc/passwd"
        )

        assertNull(result)
    }

    @Test
    fun `getProblemAssetFile should return file when it exists`() {
        val problemDir = File(tempDir, "hello-world")
        val dataDir = File(problemDir, "data")
        dataDir.mkdirs()
        val imageFile = File(dataDir, "test.png")
        imageFile.writeText("fake image data")

        val course = createActiveCourse(problemGitRepo = tempDir.absolutePath)
        every { courseRepository.findById("course-1") } returns Optional.of(course)

        val result = problemService.getProblemAssetFile(
            "student@sjsu.edu", "course-1", 1, 1, "hello-world", "data/test.png"
        )

        assertNotNull(result)
        assertEquals("test.png", result!!.name)
    }

    @Test
    fun `getProblemAssetFile should return null when file does not exist`() {
        val problemDir = File(tempDir, "hello-world")
        problemDir.mkdirs()

        val course = createActiveCourse(problemGitRepo = tempDir.absolutePath)
        every { courseRepository.findById("course-1") } returns Optional.of(course)

        val result = problemService.getProblemAssetFile(
            "student@sjsu.edu", "course-1", 1, 1, "hello-world", "nonexistent.png"
        )

        assertNull(result)
    }
}