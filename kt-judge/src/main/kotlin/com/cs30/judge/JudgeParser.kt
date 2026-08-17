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
// The leading directory varies: /tmp/ on a plain system, /work/btcache/ when the
// bt-cache-seed is active (incontainer.py sets TMPDIR=/work/btcache in that case).
private val BT_PATH_RE = Regex("""/\S+?/bapctools_\w+/problem/submissions/[^/\s]+/""")

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

// ---------- Compiler output parsing --------------------------------------------------
//
// Allowlist design: each parser extracts known-good fields (line, col, severity, message,
// context lines) and discards everything else. Unknown lines from bt, infra noise, or future
// compiler versions never reach the student — silence is safer than leaking internal details.
//
// Auto-detection: Python CE always contains 'File "...", line N'; GCC/javac always use
// 'filename.ext:line[:col]: severity:'. No language parameter required.

// Signals which parser to use.
private val PYTHON_CE_SIGNAL   = Regex("""^\s*File "[^"]+", line \d+""", RegexOption.MULTILINE)
private val GCC_JAVAC_PRESENCE = Regex("""^[^:/\s]+\.\w{1,5}:\d+""",    RegexOption.MULTILINE)

// GCC / javac primary error line. "fatal error" (e.g. missing #include) is matched first.
// No leading slash — absolute paths from other tools don't match; only bare filenames
// produced after BT_PATH_RE strips the container directory reach this regex.
private val GCC_PRIMARY_RE = Regex(
    """^[^:/\s]+\.\w{1,5}:(\d+)(?::(\d+))?:\s*(fatal error|error|warning|note):\s*(.+)$"""
)
// Summary / termination lines that are never student-useful — always discarded.
// Covers javac ("1 error", "2 warnings") and GCC ("compilation terminated.").
private val SUMMARY_RE = Regex("""^(?:\d+ (?:fatal errors?|errors?|warnings?)|compilation terminated)""")

// Python SyntaxError format: "  File "/in/submission.py", line N"
private val PYTHON_HDR_RE = Regex("""^\s*File "[^"]+", line (\d+)$""")
// Error type line: SyntaxError, IndentationError, TabError, etc.
private val PYTHON_ERR_RE = Regex("""^(\w+(?:Error|Warning|Exception)):\s*(.*)$""")

// Fallback denylist for formats with no parser yet (future languages).
private val CE_FALLBACK_RE = Regex(
    """^(?:ERROR:.*|Compiling \S+.*|Testing .*|Checking .*|Generating .*|Scanning .*)$"""
)

private data class CompilerMessage(
    val line: Int,
    val col: Int? = null,
    val severity: String,
    val text: String,
    val context: List<String> = emptyList(),
)

// C / C++ / Java: filename.ext:line[:col]: severity: message
// Context lines (GCC's "5 | code" and "  | ^~~~", javac's bare code + caret) follow each
// primary line and are accumulated up to 3 lines.
private fun parseGccJavacOutput(text: String): List<CompilerMessage> {
    val messages = mutableListOf<CompilerMessage>()
    var pending: CompilerMessage? = null
    for (line in text.lines()) {
        val m = GCC_PRIMARY_RE.find(line)
        when {
            m != null -> {
                pending?.let { messages.add(it) }
                pending = CompilerMessage(
                    line     = m.groupValues[1].toInt(),
                    col      = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt(),
                    severity = m.groupValues[3],
                    text     = m.groupValues[4].trim(),
                )
            }
            pending != null && SUMMARY_RE.containsMatchIn(line) -> {
                messages.add(pending!!)
                pending = null
            }
            pending != null && line.isNotBlank() && pending.context.size < 3 ->
                pending = pending.copy(context = pending.context + line)
            // Blank lines and unmatched lines: leave pending open (don't discard
            // a partial message on a blank gap — GCC can separate message groups).
        }
    }
    pending?.let { messages.add(it) }
    return messages
}

// Python SyntaxError/IndentationError/TabError:
//   File "/in/submission.py", line N    ← header (line number here)
//     x = 1 +                           ← code context
//            ^                          ← caret context
//   SyntaxError: invalid syntax         ← error type + message
// Python puts the type AFTER the context, so we collect context first.
private fun parsePythonOutput(text: String): List<CompilerMessage> {
    val messages = mutableListOf<CompilerMessage>()
    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        val hdr = PYTHON_HDR_RE.find(lines[i])
        if (hdr == null) { i++; continue }
        val lineNum = hdr.groupValues[1].toInt()
        i++
        val context = mutableListOf<String>()
        while (i < lines.size && context.size < 2
            && PYTHON_HDR_RE.find(lines[i]) == null
            && PYTHON_ERR_RE.find(lines[i]) == null
        ) {
            if (lines[i].isNotBlank()) context.add(lines[i])
            i++
        }
        val errMatch = if (i < lines.size) PYTHON_ERR_RE.find(lines[i]) else null
        if (errMatch != null) {
            messages.add(CompilerMessage(
                line     = lineNum,
                severity = errMatch.groupValues[1],   // "SyntaxError", "IndentationError", …
                text     = errMatch.groupValues[2].trim(),
                context  = context,
            ))
            i++
        } else if (context.isNotEmpty()) {
            // Error-type line not found — emit what we have so the student sees something.
            messages.add(CompilerMessage(line = lineNum, severity = "error", text = "syntax error", context = context))
        }
    }
    return messages
}

// Rendered as "Line N[, Col M]: severity: message\n[context lines]".
// Severity for Python is the exception class name (SyntaxError, IndentationError, …) so
// students see the familiar "Line 5: SyntaxError: invalid syntax" form.
private fun renderMessages(messages: List<CompilerMessage>): String =
    messages.joinToString("\n") { msg ->
        val loc = "Line ${msg.line}" + (msg.col?.let { ", Col $it" } ?: "")
        (listOf("$loc: ${msg.severity}: ${msg.text}") + msg.context).joinToString("\n")
    }.trim()

// Last-resort for unrecognised formats (future languages without a parser).
// Applies path-stripping and a denylist of obvious bt noise; always returns non-blank.
private fun fallbackClean(stripped: String): String =
    stripped
        .lineSequence()
        .filter { it.isNotBlank() && !CE_FALLBACK_RE.containsMatchIn(it) }
        .joinToString("\n") { line ->
            line.replace(Regex("/(?:[^/\\s]+/)+([^/:\\s]+(?::\\d+)*:?)"), "$1")
        }
        .trim()
        .ifBlank { "Compile error" }

// ---------- End compiler output parsing ----------------------------------------------

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

    fun cleanCompileOutput(text: String): String {
        val stripped = stripBtNoise(text)
        val messages = when {
            PYTHON_CE_SIGNAL.containsMatchIn(stripped)   -> parsePythonOutput(stripped)
            GCC_JAVAC_PRESENCE.containsMatchIn(stripped) -> parseGccJavacOutput(stripped)
            else                                         -> emptyList()
        }
        return if (messages.isNotEmpty()) renderMessages(messages) else fallbackClean(stripped)
    }

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
