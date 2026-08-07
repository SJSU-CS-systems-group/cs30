import com.cs30.cli.assignmentNameCollisions
import com.cs30.cli.canvasAssignmentName
import com.cs30.cli.normalizeAssignmentName
import com.cs30.server.service.CanvasProblemPlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Assignments are often created in Canvas by hand, so the derived name has to match that convention
 * exactly. A name that is off by a word or by case silently syncs nothing.
 */
class CanvasAssignmentNameTest {

    @Test
    fun `no note gives a bare lab name`() {
        assertEquals("LAB0", canvasAssignmentName(0, null))
        assertEquals("LAB1", canvasAssignmentName(1, ""))
        assertEquals("LAB12", canvasAssignmentName(12, "   "))
    }

    @Test
    fun `the first word of the note becomes the suffix`() {
        assertEquals("LAB0-Bonus", canvasAssignmentName(0, "Bonus"))
        assertEquals("LAB0-Bonus", canvasAssignmentName(0, "Bonus problems for extra credit"))
        assertEquals("LAB3-Extra", canvasAssignmentName(3, "  Extra credit  "))
    }

    @Test
    fun `punctuation around the first word is dropped`() {
        assertEquals("LAB0-Bonus", canvasAssignmentName(0, "Bonus: harder version"))
        assertEquals("LAB0-Bonus", canvasAssignmentName(0, "(Bonus) optional"))
        assertEquals("LAB0", canvasAssignmentName(0, "!!!"), "a note with no word characters adds nothing")
    }

    @Test
    fun `names compare case-insensitively so hand-typed assignments still match`() {
        assertEquals(normalizeAssignmentName("LAB0-Bonus"), normalizeAssignmentName("lab0-bonus"))
        assertEquals(normalizeAssignmentName("LAB0"), normalizeAssignmentName("  Lab0 "))
    }

    @Test
    fun `two problems without notes collide`() {
        val collisions = assignmentNameCollisions(
            0,
            listOf(CanvasProblemPlan("babyshark", null), CanvasProblemPlan("pascalmagic", null)),
        )
        assertEquals(1, collisions.size)
        assertEquals(listOf("babyshark", "pascalmagic"), collisions["LAB0"])
    }

    @Test
    fun `notes sharing a first word collide`() {
        val collisions = assignmentNameCollisions(
            2,
            listOf(CanvasProblemPlan("a", "Bonus one"), CanvasProblemPlan("b", "Bonus two")),
        )
        assertEquals(listOf("a", "b"), collisions["LAB2-Bonus"])
    }

    @Test
    fun `distinct notes do not collide`() {
        val collisions = assignmentNameCollisions(
            1,
            listOf(
                CanvasProblemPlan("main", null),
                CanvasProblemPlan("extra", "Bonus"),
                CanvasProblemPlan("third", "Challenge round"),
            ),
        )
        assertTrue(collisions.isEmpty(), "expected no collisions, got $collisions")
    }
}
