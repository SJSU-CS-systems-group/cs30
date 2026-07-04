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
}
