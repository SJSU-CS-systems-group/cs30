package com.cs30.judge

import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

data class SelfTestResult(
    val ok: Boolean,
    val verdict: String,
    val passed: Int,
    val total: Int,
    val detail: String,
)

// End-to-end self-test: grade a known-good solution against a tiny built-in
// problem and confirm it comes back AC. Passing this proves the whole pipeline
// works (docker run, bt compile+run, the validator, the parser, verdict mapping)
// not just that Docker and the image exist. Runs through the real submitSync
// path, so it exercises admission, staging, and grading exactly like /submit.
@Component
class JudgeSelfTest(private val store: JudgeStore) {

    private val problemId = "selftest"
    private val solution = "a, b = map(int, input().split())\nprint(a + b)\n"

    // Materialize the built-in problem package once; reused across calls.
    private val poolPath: Path by lazy { materialize() }

    private fun materialize(): Path {
        val pool = Files.createTempDirectory("judge-selftest-")
        val problem = pool.resolve(problemId)
        Files.createDirectories(problem.resolve("data/sample"))
        Files.writeString(
            problem.resolve("problem.yaml"),
            """
            problem_format_version: 2025-09
            uuid: 22222222-2222-2222-2222-222222222222
            name: Add Two Numbers
            type: pass-fail
            limits:
              time_limit: 5
            """.trimIndent() + "\n",
        )
        Files.writeString(problem.resolve("data/sample/1.in"), "2 3\n")
        Files.writeString(problem.resolve("data/sample/1.ans"), "5\n")
        // The container reads the mounted package as its own uid, so make the
        // package world-readable (files) and traversable (dirs).
        Files.walk(problem).use { paths ->
            paths.forEach { p ->
                val f = p.toFile()
                f.setReadable(true, false)
                if (f.isDirectory) f.setExecutable(true, false)
            }
        }
        pool.toFile().deleteOnExit()
        return pool
    }

    fun run(): SelfTestResult =
        try {
            val req = SubmitRequest(
                problemId = problemId,
                poolPath = poolPath.toString(),
                language = "python",
                source = solution,
            )
            val r = store.submitSync(req)
            val ok = r.status == "AC" && r.total > 0 && r.passed == r.total
            SelfTestResult(
                ok = ok,
                verdict = r.status,
                passed = r.passed,
                total = r.total,
                detail = if (ok) "graded built-in problem AC (${r.passed}/${r.total})"
                else "expected AC, got ${r.status} (${r.passed}/${r.total})",
            )
        } catch (e: Exception) {
            SelfTestResult(false, "ERROR", 0, 0, "${e.javaClass.simpleName}: ${e.message}")
        }
}
