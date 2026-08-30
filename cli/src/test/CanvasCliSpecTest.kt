import com.cs30.cli.Course2Canvas
import com.cs30.cli.Submissions2Canvas
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import picocli.CommandLine
import picocli.CommandLine.MissingParameterException

/**
 * Guards the picocli wiring of the Canvas commands. Building a CommandLine is what validates option
 * names, so a duplicate or malformed name fails here instead of at CLI startup, where it would break
 * every subcommand and not just this one.
 *
 * The command is built from an instance with mocked services: Spring supplies these through an
 * IFactory at runtime, and parsing arguments never touches them.
 */
class CanvasCliSpecTest {

    private fun spec() = CommandLine(Course2Canvas(mockk(relaxed = true), mockk(relaxed = true)))

    @Test
    fun `course2canvas option names are valid and unique`() {
        val cmd = spec()
        assertEquals("course2canvas", cmd.commandName)
    }

    private val required = arrayOf(
        "--cs30-course-code", "CS30", "--cs30-year", "2026", "--cs30-semester", "Fall",
        "--cs30-section", "1", "--cs30-lab", "1", "--canvas-course", "123",
    )

    @Test
    fun `a bare run is a dry run`() {
        val cmd = spec()
        val parsed = cmd.parseArgs(*required)
        assertTrue(parsed.hasMatchedOption("--cs30-course-code"))
        assertTrue(
            cmd.getCommand<Course2Canvas>().dryrun,
            "with no flags the command must not change Canvas",
        )
    }

    @Test
    fun `--no-dryrun turns off the dry run`() {
        val cmd = spec()
        cmd.parseArgs(*required, "--no-dryrun")
        assertEquals(false, cmd.getCommand<Course2Canvas>().dryrun)
    }

    @Test
    fun `--dryrun keeps the dry run on`() {
        val cmd = spec()
        cmd.parseArgs(*required, "--dryrun")
        assertTrue(cmd.getCommand<Course2Canvas>().dryrun)
    }

    @Test
    fun `force defaults to false and can be negated`() {
        val cmd = spec()
        cmd.parseArgs(
            "--cs30-course-code", "CS30", "--cs30-year", "2026", "--cs30-semester", "Fall",
            "--cs30-section", "1", "--cs30-lab", "1", "--canvas-course", "123", "--force",
        )
        assertEquals(true, cmd.getCommand<Course2Canvas>().force)
    }

    @Test
    fun `submissions2canvas is a bare dry run by default`() {
        val cmd = CommandLine(Submissions2Canvas(mockk(relaxed = true), mockk(relaxed = true)))
        assertEquals("submissions2canvas", cmd.commandName)
        cmd.parseArgs(*required)
        val command = cmd.getCommand<Submissions2Canvas>()
        assertTrue(command.dryrun, "with no flags the command must not post to Canvas")
        assertEquals(false, command.forceComment)
    }

    @Test
    fun `submissions2canvas accepts --no-dryrun and --force-comment`() {
        val cmd = CommandLine(Submissions2Canvas(mockk(relaxed = true), mockk(relaxed = true)))
        cmd.parseArgs(*required, "--no-dryrun", "--force-comment")
        val command = cmd.getCommand<Submissions2Canvas>()
        assertEquals(false, command.dryrun)
        assertEquals(true, command.forceComment)
    }

    @Test
    fun `optional canvas section rubric and group are parsed`() {
        val cmd = spec()
        cmd.parseArgs(
            "--cs30-course-code", "CS30", "--cs30-year", "2026", "--cs30-semester", "Fall",
            "--cs30-section", "2", "--cs30-lab", "3", "--canvas-course", "practice",
            "--canvas-section", "Section 2", "--rubric", "Lab Rubric",
            "--assignment-group", "labs",
        )
        val command = cmd.getCommand<Course2Canvas>()
        assertEquals("practice", command.canvasCourse)
        assertEquals("Section 2", command.canvasSection)
        assertEquals("Lab Rubric", command.rubric)
        assertEquals("labs", command.assignmentGroup)
        assertEquals(3, command.lab)
    }

    @Test
    fun `submissions2canvas needs only a code fragment and a lab to name the cs30 course`() {
        val cmd = CommandLine(Submissions2Canvas(mockk(relaxed = true), mockk(relaxed = true)))
        cmd.parseArgs("--cs30-course-code", "cs3", "--cs30-lab", "2", "--canvas-course", "practice")
        val command = cmd.getCommand<Submissions2Canvas>()
        assertEquals("cs3", command.code)
        assertNull(command.year, "an omitted year must not filter on 0")
        assertNull(command.semester)
        assertNull(command.section, "an omitted section must not filter on 0")
        assertEquals(2, command.lab)
        assertEquals("practice", command.canvasCourse)
    }

    @Test
    fun `submissions2canvas narrowing options are parsed when given`() {
        val cmd = CommandLine(Submissions2Canvas(mockk(relaxed = true), mockk(relaxed = true)))
        cmd.parseArgs(
            "--cs30-course-code", "cs30", "--cs30-year", "2026", "--cs30-semester", "fa",
            "--cs30-section", "2", "--cs30-lab", "1", "--canvas-course", "123",
        )
        val command = cmd.getCommand<Submissions2Canvas>()
        assertEquals(2026, command.year)
        assertEquals("fa", command.semester)
        assertEquals(2, command.section)
    }

    @Test
    fun `submissions2canvas still requires the code, lab and canvas course`() {
        val cmd = CommandLine(Submissions2Canvas(mockk(relaxed = true), mockk(relaxed = true)))
        assertThrows(MissingParameterException::class.java) {
            cmd.parseArgs("--cs30-lab", "1", "--canvas-course", "123")
        }
    }

    @Test
    fun `course2canvas still spells out the whole cs30 course`() {
        assertThrows(MissingParameterException::class.java) {
            spec().parseArgs("--cs30-course-code", "CS30", "--cs30-lab", "1", "--canvas-course", "123")
        }
    }
}
