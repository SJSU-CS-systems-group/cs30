package com.cs30.judge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Pure logic: bt output -> the right verdict type and counts.
class JudgeParserTest {

    @Test fun `all AC gives AC with full pass count`() {
        val v = JudgeParser.parseRunOutput(
            "sol.py: AC 0.01s @ sample/1\nsol.py: AC 0.02s @ sample/2\n", "", 0,
        )
        assertEquals(Status.AC, v.status)
        assertEquals(2, v.passed)
        assertEquals(2, v.total)
    }

    @Test fun `mixed verdicts pick the worst`() {
        val v = JudgeParser.parseRunOutput(
            "sol.py: AC 0.01s @ s/1\nsol.py: WA 0.02s @ s/2  permitted: [AC]\n", "", 0,
        )
        assertEquals(Status.WA, v.status)
        assertEquals(1, v.passed)
        assertEquals(2, v.total)
    }

    @Test fun `TLE outranks WA and AC`() {
        val v = JudgeParser.parseRunOutput(
            "sol.py: AC 0.01s @ a\nsol.py: WA 0.02s @ b\nsol.py: TLE 1.0s @ c\n", "", 0,
        )
        assertEquals(Status.TLE, v.status)
    }

    @Test fun `no testcases with a compile signal is CE`() {
        val v = JudgeParser.parseRunOutput("Build submissions: sol Failed\ncompilation error\n", "", 1)
        assertEquals(Status.CE, v.status)
        assertTrue(v.testcases.isEmpty())
    }

    @Test fun `no testcases and no compile signal is JE`() {
        val v = JudgeParser.parseRunOutput("some unrelated output\n", "", 1)
        assertEquals(Status.JE, v.status)
    }

    @Test fun `isMemoryError detects OOM across languages`() {
        assertTrue(JudgeParser.isMemoryError("java.lang.OutOfMemoryError: Java heap space"))
        assertTrue(JudgeParser.isMemoryError("terminate called after throwing an instance of 'std::bad_alloc'"))
        assertTrue(JudgeParser.isMemoryError("MemoryError"))
        assertFalse(JudgeParser.isMemoryError("all good"))
        assertFalse(JudgeParser.isMemoryError(null))
    }

    @Test fun `stripBtNoise keeps program stderr and drops bt chatter`() {
        val cleaned = JudgeParser.stripBtNoise("Running: sol.py\nboom traceback\nDone: 0.5s\n")
        assertTrue(cleaned.contains("boom traceback"))
        assertFalse(cleaned.contains("Running:"))
        assertFalse(cleaned.contains("Done:"))
    }

    // Real line, captured from a student-visible sample case on an interactive problem. bt lints the
    // problem package and writes findings to stderr; the student saw this above their own output.
    @Test fun `stripBtNoise drops bt package-lint warnings`() {
        val real = "WARNING: Problem titles in problem.en.tex (Skyline Reconstruction) and " +
            "problem.yaml (skyline reconstruction) differ; consider using \\problemname{}.\n" +
            ">? 1 19\n<861\n"
        val cleaned = JudgeParser.stripBtNoise(real)
        assertFalse(cleaned.contains("WARNING"))
        assertFalse(cleaned.contains("problemname"))
        // The interactive transcript is the student's own conversation — it must survive.
        assertTrue(cleaned.contains(">? 1 19"))
        assertTrue(cleaned.contains("<861"))
    }

    // A submission's own stderr may say WARNING; only bt's package lint is noise.
    @Test fun `stripBtNoise keeps a submission's own WARNING output`() {
        val cleaned = JudgeParser.stripBtNoise("WARNING: retrying connection\nresult=7\n")
        assertTrue(cleaned.contains("WARNING: retrying connection"))
        assertTrue(cleaned.contains("result=7"))
    }

    // btCrashed: the signal that per-case results describe a PARTIAL run. Text below is real output
    // captured during load testing, not invented.

    @Test fun `btCrashed detects a python traceback in bt output`() {
        val real = """
            Run: using timelimit: 2.0s
            nikil_ac_385_queries.cpp:  AC 0.060s @ secret/10
            nikil_ac_385_queries.cpp:  AC 0.085s @ secret/13
            Traceback (most recent call last):
              File "/usr/local/bin/bt", line 8, in <module>
                sys.exit(main())
              File "/usr/local/lib/python3.12/site-packages/bapctools/cli.py", line 1
            PermissionError: [Errno 1] Operation not permitted
        """.trimIndent()
        assertTrue(JudgeParser.btCrashed(real))
    }

    @Test fun `btCrashed is false for a healthy run`() {
        // Includes bt's non-fatal bits-stdc++ complaint, which makes bt exit non-zero on a run that
        // nonetheless graded every case — the reason exit code cannot be used as the crash signal.
        val healthy = """
            Build submissions: answer.cpp Must not depend on bits/stdc++.h.
            Run: using timelimit: 8.0s
            answer.cpp:  AC 0.004s @ secret/pascalmagic-29
            answer.cpp:  AC 0.005s @ secret/pascalmagic-17  slowest:  AC 0.005s @ secret/pascalmagic-17
        """.trimIndent()
        assertFalse(JudgeParser.btCrashed(healthy))
    }

    @Test fun `btCrashed is false for empty output`() {
        assertFalse(JudgeParser.btCrashed(""))
    }

    @Test fun `btCrashed does not fire on the word traceback in a program's own stderr`() {
        // A student's program printing the word is not bt crashing; the marker is anchored to the
        // start of a line and to Python's exact wording.
        assertFalse(JudgeParser.btCrashed("sol.py: RTE 0.01s @ secret/1  see traceback above for details"))
    }

    // cleanCompileOutput: structured parsers replace denylist regexes.
    // Primary path: GCC/javac or Python parser → known-good content only.
    // Fallback: unrecognised format → path-stripping, never blank.

    // ---- GCC / C++ parser ----

    @Test fun `cleanCompileOutput GCC cpp error with line and column`() {
        val raw = "submission.cpp:5:3: error: 'x' was not declared in this scope"
        assertEquals(
            "Line 5, Col 3: error: 'x' was not declared in this scope",
            JudgeParser.cleanCompileOutput(raw)
        )
    }

    @Test fun `cleanCompileOutput GCC preserves code-context and caret lines`() {
        val raw = "submission.cpp:7:10: error: use of undeclared identifier 'foo'\n" +
            "    7 |     foo();\n" +
            "      |     ^~~"
        assertEquals(
            "Line 7, Col 10: error: use of undeclared identifier 'foo'\n" +
                "    7 |     foo();\n" +
                "      |     ^~~",
            JudgeParser.cleanCompileOutput(raw)
        )
    }

    @Test fun `cleanCompileOutput GCC multiple errors each with note`() {
        val raw = """
            submission.cpp:3:5: error: expected ';' before 'return'
                3 |     int x = 1
                  |         ^
            submission.cpp:3:5: note: insert ';'
                3 |     int x = 1
                  |          ^
            submission.cpp:8:1: error: expected declaration before '}' token
                8 | }
                  | ^
        """.trimIndent()
        val out = JudgeParser.cleanCompileOutput(raw)
        assertTrue(out.contains("Line 3, Col 5: error:"), "First error must appear")
        assertTrue(out.contains("Line 3, Col 5: note:"), "Note must appear")
        assertTrue(out.contains("Line 8, Col 1: error:"), "Second error must appear")
        assertFalse(out.contains("submission.cpp"), "Internal filename must not appear")
    }

    @Test fun `cleanCompileOutput GCC fatal error for missing include`() {
        val raw = "submission.cpp:1:10: fatal error: abc.h: No such file or directory\n" +
            " #include <abc.h>\n" +
            "          ^~~~~~~\n" +
            "compilation terminated."
        val out = JudgeParser.cleanCompileOutput(raw)
        assertTrue(out.contains("Line 1, Col 10: fatal error:"), "Fatal error must appear")
        assertFalse(out.contains("submission.cpp"), "Internal filename must not appear")
        assertFalse(out.contains("compilation terminated"), "Compiler summary must be discarded")
    }

    @Test fun `cleanCompileOutput GCC discards 'In function' lines`() {
        val raw = "submission.cpp: In function 'main':\nsubmission.cpp:5:3: error: expected ';'"
        val out = JudgeParser.cleanCompileOutput(raw)
        assertFalse(out.contains("In function"), "Function context lines must be discarded")
        assertTrue(out.contains("Line 5, Col 3: error:"))
    }

    // ---- javac / Java parser ----

    @Test fun `cleanCompileOutput javac error with context and summary discarded`() {
        val raw = "Main.java:10: error: ';' expected\n    System.out.println(\"hi\")\n                              ^\n1 error"
        val out = JudgeParser.cleanCompileOutput(raw)
        assertTrue(out.startsWith("Line 10: error: ';' expected"), "Expected 'Line 10:' prefix, got: $out")
        assertTrue(out.contains("System.out.println"), "Code context line must survive")
        assertTrue(out.contains("^"), "Caret line must survive")
        assertFalse(out.contains("Main.java"), "Internal filename must not appear")
        assertFalse(out.contains("1 error"), "Summary line must be discarded")
    }

    @Test fun `cleanCompileOutput javac multiple errors`() {
        val raw = "Solution.java:5: error: ';' expected\n    a = 1\n         ^\n" +
            "Solution.java:9: error: ';' expected\n    b = 2\n         ^\n2 errors"
        val out = JudgeParser.cleanCompileOutput(raw)
        assertTrue(out.contains("Line 5: error:"), "First error must appear")
        assertTrue(out.contains("Line 9: error:"), "Second error must appear")
        assertFalse(out.contains("Solution.java"), "Internal filename must not appear")
        assertFalse(out.contains("2 errors"), "Summary must be discarded")
    }

    // ---- Python parser ----

    @Test fun `cleanCompileOutput Python SyntaxError`() {
        val raw = "  File \"submission.py\", line 7\n    x = 1 +\n           ^\nSyntaxError: invalid syntax"
        val out = JudgeParser.cleanCompileOutput(raw)
        assertTrue(out.startsWith("Line 7: SyntaxError: invalid syntax"), "Expected 'Line 7: SyntaxError:' prefix, got: $out")
        assertTrue(out.contains("x = 1 +"), "Code context must survive")
        assertFalse(out.contains("submission.py"), "Internal filename must not appear")
        assertFalse(out.contains("File \""), "Python File header must not appear")
    }

    @Test fun `cleanCompileOutput Python IndentationError`() {
        val raw = "  File \"submission.py\", line 3\n    pass\n       ^\nIndentationError: unexpected indent"
        val out = JudgeParser.cleanCompileOutput(raw)
        assertTrue(out.contains("Line 3: IndentationError: unexpected indent"))
        assertFalse(out.contains("submission.py"))
    }

    @Test fun `cleanCompileOutput Python preserves caret context`() {
        val raw = "  File \"submission.py\", line 5\n    y = (1 + 2\n         ^\nSyntaxError: '(' was never closed"
        val out = JudgeParser.cleanCompileOutput(raw)
        assertTrue(out.contains("y = (1 + 2"), "Code context must survive")
        assertTrue(out.contains("^"), "Caret must survive")
    }

    // ---- Fallback ----

    @Test fun `cleanCompileOutput fallback for unrecognised format strips paths and is non-blank`() {
        val out = JudgeParser.cleanCompileOutput("some unrecognised compiler chatter\nerror in compilation")
        assertTrue(out.isNotBlank(), "Fallback must never return blank")
        assertTrue(out.contains("error in compilation"), "Useful content must survive")
    }

    @Test fun `cleanCompileOutput fallback strips bt noise lines`() {
        val out = JudgeParser.cleanCompileOutput("ERROR: some bt infrastructure error\nERROR: another infra error")
        // All lines match the denylist → nothing survives → generic message
        assertEquals("Compile error", out)
    }

    // BT_PATH_RE path-prefix stripping: bt creates its temp dir under TMPDIR,
    // which incontainer.py sets to /work/btcache/ when a bt-cache-seed is present.
    // If only /tmp/ is handled, the structured parser never fires on those hosts.

    @Test fun `stripBtNoise strips work-btcache path set by incontainer TMPDIR`() {
        val raw = "/work/btcache/bapctools_abc123/problem/submissions/solution/submission.cpp:5:3: error: msg"
        val stripped = JudgeParser.stripBtNoise(raw)
        assertTrue(stripped.startsWith("submission.cpp:"), "Path must be stripped; got: $stripped")
    }

    @Test fun `cleanCompileOutput uses structured parser when path is work-btcache`() {
        val raw = "/work/btcache/bapctools_abc123/problem/submissions/solution/" +
            "submission.cpp:5:3: error: 'x' was not declared in this scope"
        val out = JudgeParser.cleanCompileOutput(raw)
        assertTrue(out.contains("Line 5, Col 3: error:"), "Structured parser must fire; got: $out")
        assertFalse(out.contains("submission.cpp"), "Internal filename must not appear in output")
    }

    @Test fun `cleanCompileOutput uses structured parser when path is under tmp`() {
        val raw = "/tmp/bapctools_xyz789/problem/submissions/solution/" +
            "submission.cpp:3:1: error: expected ';' before 'return'"
        val out = JudgeParser.cleanCompileOutput(raw)
        assertTrue(out.contains("Line 3, Col 1: error:"), "Structured parser must fire; got: $out")
        assertFalse(out.contains("submission.cpp"), "Internal filename must not appear in output")
    }
}
