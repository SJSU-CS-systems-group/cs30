package com.cs30.judge

// One bt per-case verdict line: "<sub>: AC 0.012s @ sample/1 [permitted: [AC]] <detail>".
private val PER_CASE_RE = Regex(
    """^\s*\S+:\s+(AC|WA|TLE|RTE|MLE|CE|JE)\s+([\d.]+)s\s+@\s+(\S+)""" +
        """(?:\s+permitted:\s+\[[^\]]+\])?(?:\s+(.*))?$""",
)

// bt's own progress/diagnostic lines (anchored to bt's wording), stripped so a
// program's real stderr is what remains.
private val BT_NOISE_RE = Regex(
    """^(?:ERROR: problem:.*|PROBLEM\s.*|Building (?:output|input) validators?.*""" +
        """|Build submissions?:.*|Run: using timelimit:.*|Running:\s.*""" +
        """|Running \S+:\s\S+.*|Done:\s+[\d.]+s.*)$""",
)

// bt references the submission by its internal container path; rewrite to bare name.
private val BT_PATH_RE = Regex("""/tmp/bapctools_\w+/problem/submissions/[^/\s]+/""")

// An OOM surfaces as RTE; detect it to relabel RTE -> MLE.
private val MEMORY_ERR_RE = Regex(
    "MemoryError|bad_alloc|OutOfMemoryError|Cannot allocate memory|out of memory",
    RegexOption.IGNORE_CASE,
)

object JudgeParser {
    fun isMemoryError(text: String?): Boolean = text != null && MEMORY_ERR_RE.containsMatchIn(text)

    fun cleanCompileOutput(text: String): String = stripBtNoise(text).trim()

    fun stripBtNoise(stderr: String): String =
        BT_PATH_RE.replace(stderr, "")
            .lineSequence()
            .filter { it.isNotBlank() && BT_NOISE_RE.find(it) == null }
            .joinToString("\n")

    fun parseRunOutput(stdout: String, stderr: String, returnCode: Int): Verdict {
        val cases = LinkedHashMap<String, TestcaseResult>()
        for (line in "$stdout\n$stderr".lineSequence()) {
            val m = PER_CASE_RE.find(line) ?: continue
            val g = m.groupValues
            val tc = TestcaseResult(
                name = g[3],
                status = Status.valueOf(g[1]),
                timeS = g[2].toDouble(),
                detail = g[4].ifEmpty { null },
            )
            // bt can print the same case multiple times; keep the worst verdict.
            val prev = cases[tc.name]
            if (prev == null || PRECEDENCE.indexOf(tc.status) < PRECEDENCE.indexOf(prev.status)) {
                cases[tc.name] = tc
            }
        }

        if (cases.isEmpty()) {
            val haystack = "$stdout\n$stderr".lowercase()
            val compileFailed = "compil" in haystack ||
                ("build submissions:" in haystack && "failed" in haystack)
            return Verdict(
                status = if (compileFailed) Status.CE else Status.JE,
                rawStdout = stdout, rawStderr = stderr, returnCode = returnCode,
            )
        }

        val results = cases.values.toList()
        val overall = results.minByOrNull { PRECEDENCE.indexOf(it.status) }!!.status
        return Verdict(
            status = overall,
            testcases = results,
            passed = results.count { it.status == Status.AC },
            total = results.size,
            maxTimeS = results.maxOf { it.timeS },
            rawStdout = stdout, rawStderr = stderr, returnCode = returnCode,
        )
    }
}
