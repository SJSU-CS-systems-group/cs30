package com.cs30.judge

// One bt per-case verdict line: "<sub>: AC 0.012s @ sample/1 [permitted: [AC]] <detail>".
private val PER_CASE_RE = Regex(
    """^\s*\S+:\s+(AC|WA|TLE|RTE|MLE|CE|JE)\s+([\d.]+)s\s+@\s+(\S+)""" +
        """(?:\s+permitted:\s+\[[^\]]+\])?(?:\s+(.*))?$""",
)

// bt's own progress/diagnostic lines (anchored to bt's wording), stripped so a
// program's real stderr is what remains.
//
// The WARNING branch requires a package-file reference on purpose: bt's package lint (e.g. mismatched
// titles in problem.yaml vs problem.en.tex) is for the problem's author, not the student, but a
// submission may print a bare "WARNING:" of its own and that output must survive.
private val BT_NOISE_RE = Regex(
    """^(?:ERROR: problem:.*|PROBLEM\s.*|Building (?:output|input) validators?.*""" +
        """|Build submissions?:.*|Run: using timelimit:.*|Running:\s.*""" +
        """|Running \S+:\s\S+.*|Done:\s+[\d.]+s.*""" +
        """|WARNING:.*(?:problem\.yaml|problem\.[a-z]{2}\.tex|problem\.tex|testdata\.yaml).*)$""",
)

// bt references the submission by its internal container path; rewrite to bare name.
private val BT_PATH_RE = Regex("""/tmp/bapctools_\w+/problem/submissions/[^/\s]+/""")

// An OOM surfaces as RTE; detect it to relabel RTE -> MLE.
private val MEMORY_ERR_RE = Regex(
    "MemoryError|bad_alloc|OutOfMemoryError|Cannot allocate memory|out of memory",
    RegexOption.IGNORE_CASE,
)

// bt is a Python program: a traceback means it died partway, so any per-case lines it emitted describe
// an INCOMPLETE run. A healthy run never prints one, so this is a signal rather than a heuristic.
// Observed under load — bt died on PermissionError from fcntl(F_SETPIPE_SZ) after 2 of 100 cases; both
// had passed, so the verdict computed to AC and the student was shown "AC (2/2 passed)".
private val PY_TRACEBACK_RE = Regex("""^Traceback \(most recent call last\):""", RegexOption.MULTILINE)

object JudgeParser {
    fun isMemoryError(text: String?): Boolean = text != null && MEMORY_ERR_RE.containsMatchIn(text)

    /**
     * Whether bt itself crashed, meaning any per-case results in this output are a partial run.
     *
     * Deliberately NOT based on bt's exit code: bt returns a non-zero exit even for fully successful
     * runs (it exits 1 while complaining `Must not depend on bits/stdc++.h`, having graded every case),
     * so the exit code cannot distinguish success from failure.
     */
    fun btCrashed(text: String): Boolean = PY_TRACEBACK_RE.containsMatchIn(text)

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
