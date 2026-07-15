import com.cs30.server.models.Course
import com.cs30.server.models.Problem
import com.cs30.server.models.ScheduledLab
import com.cs30.server.repository.CourseRepository
import com.cs30.server.service.LabService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LabServiceTest {

    private lateinit var courseRepository: CourseRepository
    private lateinit var labService: LabService

    @BeforeEach
    fun setUp() {
        courseRepository = mockk(relaxed = true)
        labService = LabService(courseRepository)
    }

    private fun createCourse(language: String = "Java"): Course {
        return Course(
            id = "course-1",
            code = "CS-101",
            section = 1,
            year = 2024,
            semester = "Fall",
            language = language
        )
    }

    // ==================== addProblemToLab tests ====================

    @Test
    fun `addProblemToLab should create lab and add problem when lab does not exist`() {
        val course = createCourse()
        every { courseRepository.save(any()) } answers { firstArg() }

        val result = labService.addProblemToLab(course, 1, "hello-world")

        assertEquals("Added problem 'hello-world' to Lab 1 (language: Java)", result)
        assertEquals(1, course.labs.size)
        assertEquals(1, course.labs[0].problems.size)
        assertEquals("hello-world", course.labs[0].problems[0].name)
        verify { courseRepository.save(course) }
    }

    @Test
    fun `addProblemToLab should add problem to existing lab`() {
        val course = createCourse()
        val lab = ScheduledLab(labNumber = 1)
        course.addLab(lab)
        every { courseRepository.save(any()) } answers { firstArg() }

        val result = labService.addProblemToLab(course, 1, "fizz-buzz")

        assertEquals("Added problem 'fizz-buzz' to Lab 1 (language: Java)", result)
        assertEquals(1, course.labs.size)
        assertEquals(1, lab.problems.size)
    }

    @Test
    fun `addProblemToLab should update existing problem`() {
        val course = createCourse()
        val lab = ScheduledLab(labNumber = 1)
        lab.addProblem(Problem(name = "existing-problem", language = "Python"))
        course.addLab(lab)
        every { courseRepository.save(any()) } answers { firstArg() }

        val result = labService.addProblemToLab(course, 1, "existing-problem", "Java")

        assertEquals("Updated problem 'existing-problem' in Lab 1 (language: Java)", result)
        assertEquals(1, lab.problems.size)
        assertEquals("Java", lab.problems[0].language)
    }

    @Test
    fun `addProblemToLab should use course language when not specified`() {
        val course = createCourse(language = "Python")
        every { courseRepository.save(any()) } answers { firstArg() }

        labService.addProblemToLab(course, 1, "test-problem")

        assertEquals("Python", course.labs[0].problems[0].language)
    }

    @Test
    fun `addProblemToLab should use specified language over course language`() {
        val course = createCourse(language = "Python")
        every { courseRepository.save(any()) } answers { firstArg() }

        labService.addProblemToLab(course, 1, "test-problem", "Java")

        assertEquals("Java", course.labs[0].problems[0].language)
    }

    // ==================== removeProblemFromLab tests ====================

    @Test
    fun `removeProblemFromLab should remove existing problem`() {
        val course = createCourse()
        val lab = ScheduledLab(labNumber = 1)
        lab.addProblem(Problem(name = "to-remove", language = "Java"))
        course.addLab(lab)
        every { courseRepository.save(any()) } answers { firstArg() }

        val result = labService.removeProblemFromLab(course, 1, "to-remove")

        assertEquals("Removed problem 'to-remove' from Lab 1", result)
        assertTrue(lab.problems.isEmpty())
        verify { courseRepository.save(course) }
    }

    @Test
    fun `removeProblemFromLab should return error when lab not found`() {
        val course = createCourse()

        val result = labService.removeProblemFromLab(course, 99, "any-problem")

        assertEquals("Lab 99 not found in course", result)
        verify(exactly = 0) { courseRepository.save(any()) }
    }

    @Test
    fun `removeProblemFromLab should return error when problem not found`() {
        val course = createCourse()
        val lab = ScheduledLab(labNumber = 1)
        course.addLab(lab)

        val result = labService.removeProblemFromLab(course, 1, "nonexistent")

        assertEquals("Problem 'nonexistent' not found in Lab 1", result)
        verify(exactly = 0) { courseRepository.save(any()) }
    }

    // ==================== updateProblemLanguage tests ====================

    @Test
    fun `updateProblemLanguage should update language`() {
        val course = createCourse()
        val lab = ScheduledLab(labNumber = 1)
        lab.addProblem(Problem(name = "problem1", language = "Java"))
        course.addLab(lab)
        every { courseRepository.save(any()) } answers { firstArg() }

        val result = labService.updateProblemLanguage(course, 1, "problem1", "Python")

        assertEquals("Updated problem 'problem1' language to 'Python' in Lab 1", result)
        assertEquals("Python", lab.problems[0].language)
    }

    @Test
    fun `updateProblemLanguage should return error when lab not found`() {
        val course = createCourse()

        val result = labService.updateProblemLanguage(course, 99, "problem1", "Python")

        assertEquals("Lab 99 not found in course", result)
    }

    @Test
    fun `updateProblemLanguage should return error when problem not found`() {
        val course = createCourse()
        val lab = ScheduledLab(labNumber = 1)
        course.addLab(lab)

        val result = labService.updateProblemLanguage(course, 1, "nonexistent", "Python")

        assertEquals("Problem 'nonexistent' not found in Lab 1", result)
    }

    // ==================== cancelLab tests ====================

    @Test
    fun `cancelLab should remove lab and all its problems`() {
        val course = createCourse()
        val lab = ScheduledLab(labNumber = 1)
        lab.addProblem(Problem(name = "p1", language = "Java"))
        lab.addProblem(Problem(name = "p2", language = "Java"))
        course.addLab(lab)
        every { courseRepository.save(any()) } answers { firstArg() }

        val results = labService.cancelLab(course, 1)

        assertEquals(2, results.size)
        assertTrue(results[0].contains("Deleted 2 problem(s)"))
        assertTrue(results[1].contains("Removed Lab 1"))
        assertTrue(course.labs.isEmpty())
        verify { courseRepository.save(course) }
    }

    @Test
    fun `cancelLab should return error when lab not found`() {
        val course = createCourse()

        val results = labService.cancelLab(course, 99)

        assertEquals(1, results.size)
        assertTrue(results[0].startsWith("ERROR:"))
    }

    @Test
    fun `cancelLab should handle lab with no problems`() {
        val course = createCourse()
        val lab = ScheduledLab(labNumber = 1)
        course.addLab(lab)
        every { courseRepository.save(any()) } answers { firstArg() }

        val results = labService.cancelLab(course, 1)

        assertEquals(1, results.size)
        assertTrue(results[0].contains("Removed Lab 1"))
        assertTrue(course.labs.isEmpty())
    }
}