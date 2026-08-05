package com.cs30.judge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Two things, both about refusing to report a verdict the run did not earn.
 *
 * [JudgeRunner.countGradedCases] is the number a submit is checked against before its verdict is
 * trusted. Getting it wrong in either direction is harmful: too low and a truncated grade is accepted as
 * complete; too high and a correct submission is rejected. The count must equal bt's own, which for
 * submit mode is every `.in` under data/sample and data/secret (bt runs with no path filter there).
 *
 * The integrity-gate tests at the bottom cover the ordering that decides whether a failed run is
 * refused or misreported as the student's own compile error.
 */
class JudgeRunnerTest {

    // JudgeRunner's constructor only stores props and extracts the orchestrator resource; no container
    // is started until runSubmit/runSamples, so a default-props instance is enough to reach the counter.
    private val runner = JudgeRunner(JudgeProperties())

    private fun caseFile(dir: Path, name: String) {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("$name.in"), "1\n")
        Files.writeString(dir.resolve("$name.ans"), "1\n")
    }

    @Test fun `counts sample and secret cases`(@TempDir root: Path) {
        val data = root.resolve("data")
        caseFile(data.resolve("sample"), "1")
        caseFile(data.resolve("sample"), "2")
        repeat(5) { caseFile(data.resolve("secret"), "s$it") }
        assertEquals(7, runner.countGradedCases(root))
    }

    @Test fun `counts cases inside nested test groups`(@TempDir root: Path) {
        // Some problems group secret cases into subdirectories; bt grades those too, so the walk must
        // be recursive. cascade ships a data/test_group.yaml, so this layout is not hypothetical.
        val data = root.resolve("data")
        caseFile(data.resolve("sample"), "1")
        caseFile(data.resolve("secret").resolve("group1"), "a")
        caseFile(data.resolve("secret").resolve("group1"), "b")
        caseFile(data.resolve("secret").resolve("group2"), "c")
        assertEquals(4, runner.countGradedCases(root))
    }

    @Test fun `ignores answer files and other extensions`(@TempDir root: Path) {
        val secret = root.resolve("data").resolve("secret")
        caseFile(secret, "1")
        Files.writeString(secret.resolve("notes.txt"), "x")
        Files.writeString(secret.resolve("2.ans"), "orphan answer, no input")
        assertEquals(1, runner.countGradedCases(root))
    }

    @Test fun `ignores directories other than sample and secret`(@TempDir root: Path) {
        // data/invalid_input and similar exist in some problem formats for input-validator testing and
        // are never graded, so they must not inflate the expected count.
        val data = root.resolve("data")
        caseFile(data.resolve("sample"), "1")
        caseFile(data.resolve("invalid_input"), "bad")
        assertEquals(1, runner.countGradedCases(root))
    }

    @Test fun `returns zero when data directory is absent`(@TempDir root: Path) {
        // 0 disables the completeness check rather than rejecting the submission — a counting failure
        // must never fail a student's correct code.
        assertEquals(0, runner.countGradedCases(root))
    }

    @Test fun `returns zero for an empty data directory`(@TempDir root: Path) {
        Files.createDirectories(root.resolve("data").resolve("secret"))
        assertEquals(0, runner.countGradedCases(root))
    }

    // ==================== integrity gate: a partial run must never yield a verdict ====================
    //
    // The orchestrator emits {"verdict_text": ..., "cases": [...]}. `verdict_text` is bt's raw output,
    // and it is the only thing these paths read, so a handwritten blob exercises them exactly.

    /**
     * bt died during the build, before grading anything: no per-case lines, a Python traceback, and
     * build chatter containing "Compiling".
     *
     * That last detail is the whole point. `JudgeParser` decides compile-error with the substring test
     * `"compil" in haystack`, so this output classifies as CE — and until the integrity gate was moved
     * above the CE branch, the CE branch returned first and handed the student "your code did not
     * compile", with bt's own traceback as the compile output, for a run that never finished.
     */
    private val crashedBeforeAnyCase = """
        {"verdict_text": "Build submissions: sol.py\nCompiling sol.py\nTraceback (most recent call last):\n  File \"/usr/local/bin/bt\", line 8, in <module>\n    sys.exit(main())\nPermissionError: [Errno 1] Operation not permitted\n", "cases": []}
    """.trimIndent()

    /** bt crashed after grading two of the problem's cases — the shape seen in production. */
    private val crashedMidway = """
        {"verdict_text": "sol.py: AC 0.01s @ sample/1\nsol.py: AC 0.02s @ sample/2\nTraceback (most recent call last):\nPermissionError: [Errno 1] Operation not permitted\n", "cases": []}
    """.trimIndent()

    /** A real compile failure: bt says so, and there is no traceback. This one must still be a verdict. */
    private val genuineCompileError = """
        {"verdict_text": "Build submissions: sol.py Failed\ncompilation error: expected ';' before '}'\n", "cases": []}
    """.trimIndent()

    @Test fun `submit - a bt crash before the first case is not reported as a compile error`() {
        val e = assertThrows(JudgeError::class.java) {
            runner.parseSubmit(crashedBeforeAnyCase, "", expectedCases = 100)
        }
        assertTrue(e.message!!.contains("the judge tool crashed"), "was: ${e.message}")
    }

    @Test fun `submit - a bt crash after some cases is refused rather than graded`() {
        assertThrows(JudgeError::class.java) {
            runner.parseSubmit(crashedMidway, "", expectedCases = 100)
        }
    }

    @Test fun `run - a bt crash is refused instead of reporting partial cases as complete`() {
        // parseSamples had no crash check at all: it would have returned a RunResult here.
        assertThrows(JudgeError::class.java) { runner.parseSamples(crashedMidway, "") }
    }

    // The two below are the over-correction guard. The cheapest way to make the tests above pass is to
    // refuse compile errors too — which would break the one class of detail students are SUPPOSED to
    // see, and would be invisible without these.

    @Test fun `submit - a genuine compile error still returns a CE verdict`() {
        val result = runner.parseSubmit(genuineCompileError, "", expectedCases = 100)
        assertEquals("CE", result.status)
        assertTrue(result.compileOutput!!.contains("compilation error"), "was: ${result.compileOutput}")
    }

    @Test fun `run - a genuine compile error still returns compile output`() {
        val result = runner.parseSamples(genuineCompileError, "")
        assertTrue(result.cases.isEmpty())
        assertTrue(result.compileOutput!!.contains("compilation error"), "was: ${result.compileOutput}")
    }

    @Test fun `submit - refuses when bt graded fewer cases than the problem has`() {
        // The load-test failure: two cases graded, both AC, on a 100-case problem. worstStatus of
        // all-AC is "AC", so without this check the student is shown a pass.
        val partial = """
            {"verdict_text": "sol.py: AC 0.01s @ sample/1\nsol.py: AC 0.02s @ sample/2\n", "cases": []}
        """.trimIndent()
        val e = assertThrows(JudgeError::class.java) { runner.parseSubmit(partial, "", expectedCases = 100) }
        assertTrue(e.message!!.contains("only 2 of 100"), "was: ${e.message}")
    }
}
