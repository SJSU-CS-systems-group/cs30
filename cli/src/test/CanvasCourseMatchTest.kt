package cli

import com.cs30.cli.CanvasCourse
import com.cs30.cli.CanvasException
import com.cs30.cli.CanvasTerm
import com.cs30.cli.selectCanvasCourse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Picking a Canvas course from a name/code fragment, and what the listings say when that fails. */
class CanvasCourseMatchTest {

    private val fall26 = CanvasCourse(
        id = 1, name = "CS 30 Fall 2026", courseCode = "CS30-F26",
        workflowState = "available", concluded = false, term = CanvasTerm(10, "Fall 2026"),
    )
    private val fall25 = CanvasCourse(
        id = 2, name = "CS 30 Fall 2025", courseCode = "CS30-F25",
        workflowState = "available", concluded = true, term = CanvasTerm(9, "Fall 2025"),
    )
    private val lab = CanvasCourse(id = 3, name = "CS 30 Lab", courseCode = "CS30L", workflowState = "unpublished")
    private val sandbox = CanvasCourse(id = 4, name = "Sandbox", courseCode = "SBX", workflowState = "completed")
    private val all = listOf(fall26, fall25, lab, sandbox)

    private fun failure(courses: List<CanvasCourse>, query: String): String =
        assertThrows(CanvasException::class.java) { selectCanvasCourse(courses, query) }.message!!

    @Test
    fun `an exact name wins over a course whose name contains it`() {
        val plain = CanvasCourse(id = 5, name = "CS30")
        val longer = CanvasCourse(id = 6, name = "CS30 Lab")

        assertEquals(plain, selectCanvasCourse(listOf(longer, plain), "cs30"))
        assertEquals(longer, selectCanvasCourse(listOf(longer, plain), "cs30 lab"))
    }

    @Test
    fun `an exact course code wins the same way`() {
        assertEquals(fall26, selectCanvasCourse(all, "cs30-f26"))
    }

    @Test
    fun `a unique substring of the name or code resolves, ignoring case`() {
        assertEquals(fall26, selectCanvasCourse(all, "F26"))
        assertEquals(sandbox, selectCanvasCourse(all, "sand"))
    }

    @Test
    fun `several matches are an error listing each with its term and state`() {
        val message = failure(all, "cs 30")

        assertTrue(message.startsWith("multiple Canvas courses match 'cs 30':"), message)
        assertTrue(message.contains("\n  - 1: CS 30 Fall 2026 (CS30-F26, Fall 2026)"), message)
        assertTrue(message.contains("\n  - 2: CS 30 Fall 2025 (CS30-F25, Fall 2025, concluded)"), message)
        assertTrue(message.contains("\n  - 3: CS 30 Lab (CS30L, unpublished)"), message)
        assertFalse(message.contains("Sandbox"), message)
        assertTrue(message.endsWith("Pass the course id or a longer fragment."), message)
    }

    @Test
    fun `two courses with the same exact name are ambiguous, not first wins`() {
        val a = CanvasCourse(id = 1, name = "CS30", term = CanvasTerm(1, "Fall 2025"))
        val b = CanvasCourse(id = 2, name = "CS30", term = CanvasTerm(2, "Fall 2026"))

        val message = failure(listOf(a, b), "CS30")

        assertTrue(message.contains("1: CS30 (Fall 2025)"), message)
        assertTrue(message.contains("2: CS30 (Fall 2026)"), message)
    }

    @Test
    fun `no match lists only the active courses`() {
        val message = failure(all, "math")

        assertTrue(message.startsWith("no Canvas course matching 'math'. Active courses:"), message)
        assertTrue(message.contains("\n  - 1: CS 30 Fall 2026 (CS30-F26, Fall 2026)"), message)
        assertTrue(message.contains("\n  - 3: CS 30 Lab (CS30L, unpublished)"), message)
        assertFalse(message.contains("Fall 2025"), message)
        assertFalse(message.contains("Sandbox"), message)
    }

    @Test
    fun `no match with nothing active says so`() {
        assertTrue(failure(listOf(fall25, sandbox), "math").endsWith("Active courses: (none)"))
    }

    @Test
    fun `active means not concluded, not completed and not deleted`() {
        assertTrue(fall26.active)
        assertTrue(lab.active, "an unpublished course is being set up, so it counts")
        assertTrue(CanvasCourse(id = 7, name = "bare").active, "no state information means no reason to hide it")
        assertFalse(fall25.active, "concluded by its term")
        assertFalse(sandbox.active, "concluded by hand")
        assertFalse(CanvasCourse(id = 8, name = "gone", workflowState = "deleted").active)
    }

    @Test
    fun `describe omits a course code that only repeats the name`() {
        assertEquals("9: CS30", CanvasCourse(id = 9, name = "CS30", courseCode = "cs30").describe())
        assertEquals("9: CS30 (CS30-01)", CanvasCourse(id = 9, name = "CS30", courseCode = "CS30-01").describe())
    }
}
