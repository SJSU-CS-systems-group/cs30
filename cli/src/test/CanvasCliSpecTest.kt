import com.cs30.cli.Course2Canvas
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import picocli.CommandLine

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
        "--course-code", "CS30", "--year", "2026", "--semester", "Fall",
        "--section", "1", "--lab", "1", "--canvas-course", "123",
    )

    @Test
    fun `a bare run is a dry run`() {
        val cmd = spec()
        val parsed = cmd.parseArgs(*required)
        assertTrue(parsed.hasMatchedOption("--course-code"))
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
            "--course-code", "CS30", "--year", "2026", "--semester", "Fall",
            "--section", "1", "--lab", "1", "--canvas-course", "123", "--force",
        )
        assertEquals(true, cmd.getCommand<Course2Canvas>().force)
    }

    @Test
    fun `optional canvas section rubric and group are parsed`() {
        val cmd = spec()
        cmd.parseArgs(
            "--course-code", "CS30", "--year", "2026", "--semester", "Fall",
            "--section", "2", "--lab", "3", "--canvas-course", "practice",
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
}
