import com.cs30.server.service.FileIoProblem
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FileIoProblemTest {

    @TempDir
    lateinit var tempDir: File

    /** A minimal valid file-I/O problem: one sample case with an argument, a starting file and an expected file. */
    private fun problem(config: String = ""): File {
        val dir = File(tempDir, "sumlst")
        File(dir, "data/sample/1.files").mkdirs()
        File(dir, "data/sample/1.expected").mkdirs()
        File(dir, "problem.yaml").writeText("problem_format_version: 2025-09\nname: Sum\ntype: pass-fail\n")
        File(dir, FileIoProblem.CONFIG).writeText(config)
        File(dir, "data/sample/1.in").writeText("")
        File(dir, "data/sample/1.yaml").writeText("args: [\"file1.lst\"]\n")
        File(dir, "data/sample/1.files/file1.lst").writeText("3\n4\n")
        File(dir, "data/sample/1.expected/file1.lst.sum").writeText("7\n")
        return dir
    }

    @Test
    fun `a problem without file_io yaml is not checked`() {
        val dir = problem()
        File(dir, FileIoProblem.CONFIG).delete()
        File(dir, "data/sample/1.ans").writeText("both")   // would clash with 1.expected in a file-I/O problem
        assertFalse(FileIoProblem.usesFiles(dir))
        assertEquals(emptyList<String>(), FileIoProblem.problems(dir))
    }

    @Test
    fun `an empty file_io yaml and valid compare options pass`() {
        assertEquals(emptyList<String>(), FileIoProblem.problems(problem()))
        assertEquals(
            emptyList<String>(),
            FileIoProblem.problems(problem("compare:\n  \"*\": {order: any}\n  file1.lst.sum: {order: any_words, unique: true}\n")),
        )
    }

    @Test
    fun `config mistakes are reported`() {
        fun errors(config: String) = FileIoProblem.problems(problem(config)).joinToString("\n")
        assertTrue(errors("input: input.txt\n").contains("the only key allowed is 'compare'"))
        assertTrue(errors("compare: [a]\n").contains("'compare' must map file names to options"))
        assertTrue(errors("compare:\n  ../x: {order: any}\n").contains("not a plain file name"))
        assertTrue(errors("compare:\n  \"*\": {sort: true}\n").contains("options are 'order' and 'unique'"))
        assertTrue(errors("compare:\n  \"*\": {order: random}\n").contains("order must be one of"))
        assertTrue(errors("compare:\n  \"*\": {order: any, unique: yes please}\n").contains("unique must be true or false"))
        assertTrue(errors("compare:\n  \"*\": {unique: true}\n").contains("unique needs order"))
        assertTrue(errors("compare:\n  typo.sum: {order: any}\n").contains("[typo.sum], which no test case expects"))
        assertTrue(errors("compare: {\n").contains("not valid YAML"))
    }

    @Test
    fun `test case mistakes are reported`() {
        val dir = problem()
        File(dir, "data/sample/1.ans").writeText("7\n")
        File(dir, "data/sample/1.files/sub").mkdirs()
        File(dir, "data/sample/1.expected/bad name.txt").writeText("")
        File(dir, "data/secret/2.expected").mkdirs()
        File(dir, "data/secret/2.in").writeText("")
        val errors = FileIoProblem.problems(dir).joinToString("\n")
        assertTrue(errors.contains("data/sample/1.ans and data/sample/1.expected both give the answer"), errors)
        assertTrue(errors.contains("starting file sub in 1.files must be a plain file"), errors)
        assertTrue(errors.contains("expected file bad name.txt in 1.expected"), errors)
        assertTrue(errors.contains("data/secret/2.expected is empty"), errors)
    }

    @Test
    fun `interactive problems are refused`() {
        val dir = problem()
        File(dir, "problem.yaml").writeText("problem_format_version: 2025-09\ntype: interactive\n")
        assertTrue(FileIoProblem.problems(dir).single().contains("does not work with interactive"))
        File(dir, "problem.yaml").writeText("validation: custom interactive\n")
        assertTrue(FileIoProblem.problems(dir).single().contains("does not work with interactive"))
    }

    @Test
    fun `a case is shown with its arguments, files and expected files`() {
        val dir = problem()
        File(dir, "data/sample/1.files/file2.lst").writeText("10")
        val (input, expected) = FileIoProblem.shownCase(File(dir, "data/sample/1.in"))
        assertEquals("=== arguments ===\nfile1.lst\n=== file1.lst ===\n3\n4\n=== file2.lst ===\n10\n", input)
        assertEquals("=== file1.lst.sum ===\n7\n", expected)
    }

    @Test
    fun `the statement renders from a copy whose samples show the files`() {
        val dir = problem()
        File(dir, "data/secret/1.expected").mkdirs()
        File(dir, "data/secret/1.in").writeText("")
        File(dir, "data/secret/1.expected/out.txt").writeText("secret\n")

        val rendered = FileIoProblem.statementSource(dir, File(tempDir, "scratch"))

        assertEquals(File(tempDir, "scratch/sumlst"), rendered)
        assertEquals("=== arguments ===\nfile1.lst\n=== file1.lst ===\n3\n4\n", File(rendered, "data/sample/1.in").readText())
        assertEquals("=== file1.lst.sum ===\n7\n", File(rendered, "data/sample/1.ans").readText())
        assertFalse(File(rendered, "data/secret/1.ans").exists(), "secret cases are not shown, so not rewritten")
        // The problem itself is untouched.
        assertEquals("", File(dir, "data/sample/1.in").readText())
        assertFalse(File(dir, "data/sample/1.ans").exists())
    }

    @Test
    fun `a problem without file_io yaml renders as is`() {
        val dir = problem()
        File(dir, FileIoProblem.CONFIG).delete()
        assertEquals(dir, FileIoProblem.statementSource(dir, File(tempDir, "scratch")))
    }

    @Test
    fun `a case without expected files is shown with its ans`() {
        val dir = problem()
        File(dir, "data/sample/1.expected").deleteRecursively()
        File(dir, "data/sample/1.yaml").delete()
        File(dir, "data/sample/1.in").writeText("5 6\n")
        File(dir, "data/sample/1.ans").writeText("11\n")
        val (input, expected) = FileIoProblem.shownCase(File(dir, "data/sample/1.in"))
        assertEquals("=== stdin ===\n5 6\n=== file1.lst ===\n3\n4\n", input)
        assertEquals("11\n", expected)
    }
}
