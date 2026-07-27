package data

import kotlinx.serialization.Serializable

@Serializable
data class TestResult(
    val testCase: Int,
    val input: String,
    val expectedOutput: String,
    val actualOutput: String,
    val passed: Boolean,
    val status: String? = null,         // per-case verdict: AC, WA, TLE, RTE, MLE, CE (null = ungraded/custom)
    val hidden: Boolean = false,        // secret testcase: input/expected/output withheld
    val executionTimeMs: Int? = null,   // judge-reported wall time; null when not provided (CE, custom cases)
    val stderr: String = "",            // runtime stderr (stack trace for RTE; empty for most other verdicts)
)

@Serializable
data class TestResultsResponse(
    val status: String,
    val results: List<TestResult>,
    // Backend-authoritative: false means grading itself failed (judge/infra error), distinct from
    // a legitimately empty results list. Defaults true so a plain, successfully-judged response
    // doesn't need to name this explicitly at every call site.
    val success: Boolean = true,
)

@Serializable
data class RunOutput(
    val status: String,
    val stdout: String,
    val stderr: String,
    val executionTimeMs: Int
)

@Serializable
data class RuntimeError(
    val status: String,
    val stderr: String
)
