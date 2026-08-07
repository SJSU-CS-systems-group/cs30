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
        assertEquals("LAB00", canvasAssignmentName(0, null))
        assertEquals("LAB01", canvasAssignmentName(1, ""))
        assertEquals("LAB12", canvasAssignmentName(12, "   "))
    }

    @Test
    fun `lab numbers are padded to two digits`() {
        assertEquals("LAB01", canvasAssignmentName(1, null))
        assertEquals("LAB09", canvasAssignmentName(9, null))
        assertEquals("LAB10", canvasAssignmentName(10, null))
        assertEquals("LAB01-Bonus", canvasAssignmentName(1, "Bonus round"))
    }

    @Test
    fun `the first word of the note becomes the suffix`() {
        assertEquals("LAB00-Bonus", canvasAssignmentName(0, "Bonus"))
        assertEquals("LAB00-Bonus", canvasAssignmentName(0, "Bonus problems for extra credit"))
        assertEquals("LAB03-Extra", canvasAssignmentName(3, "  Extra credit  "))
    }

    @Test
    fun `punctuation around the first word is dropped`() {
        assertEquals("LAB00-Bonus", canvasAssignmentName(0, "Bonus: harder version"))
        assertEquals("LAB00-Bonus", canvasAssignmentName(0, "(Bonus) optional"))
        assertEquals("LAB00", canvasAssignmentName(0, "!!!"), "a note with no word characters adds nothing")
    }

    @Test
    fun `names compare case-insensitively so hand-typed assignments still match`() {
        assertEquals(normalizeAssignmentName("LAB01-Bonus"), normalizeAssignmentName("lab01-bonus"))
        assertEquals(normalizeAssignmentName("LAB01"), normalizeAssignmentName("  Lab01 "))
    }

    @Test
    fun `two problems without notes collide`() {
        val collisions = assignmentNameCollisions(
            0,
            listOf(CanvasProblemPlan("babyshark", null), CanvasProblemPlan("pascalmagic", null)),
        )
        assertEquals(1, collisions.size)
        assertEquals(listOf("babyshark", "pascalmagic"), collisions["LAB00"])
    }

    @Test
    fun `notes sharing a first word collide`() {
        val collisions = assignmentNameCollisions(
            2,
            listOf(CanvasProblemPlan("a", "Bonus one"), CanvasProblemPlan("b", "Bonus two")),
        )
        assertEquals(listOf("a", "b"), collisions["LAB02-Bonus"])
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
