package labx.data

import kotlinx.serialization.Serializable

@Serializable
data class TestResult(
    val testCase: Int,
    val input: String,
    val expectedOutput: String,
    val actualOutput: String,
    val passed: Boolean
)

@Serializable
data class TestResultsResponse(
    val status: String,
    val results: List<TestResult>
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
