package backend

import data.RunOutput
import data.RuntimeError
import data.TestResult
import data.TestResultsResponse
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class HttpBackendService(
    private val baseUrl: String,
    private val getAuthHeader: () -> String? = { null },
) : BackendService {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun runCode(req: RunRequest): RunOutput {
        val resp = postRun(req.courseId, req.section, req.labNumber, req.problemName, req.studentEmail, req.language, req.code, req.stdin)
        val tc = resp.testcases?.firstOrNull()
        return RunOutput(
            status = if (resp.compileOutput != null) "ERROR" else "SUCCESS",
            stdout = tc?.stdout ?: "",
            stderr = resp.compileOutput ?: tc?.stderr ?: "",
            executionTimeMs = ((tc?.timeS ?: 0.0) * 1000).toInt(),
        )
    }

    override suspend fun testCode(req: TestRequest): TestResultsResponse = try {
        val resp = postRun(req.courseId, req.section, req.labNumber, req.problemName, req.studentEmail, req.language, req.code, req.stdin)
        toTestResults(resp.testcases, resp.compileOutput, runSummary(resp))
    } catch (e: Exception) {
        errorResults(e)
    }

    override suspend fun submitCode(req: SubmitRequest): SubmissionResult = try {
        val body = json.encodeToString(
            SubmitCodeRequestDto(
                courseId = req.courseId, section = req.section, labNumber = req.labNumber,
                problemName = req.problemName, studentEmail = req.studentEmail,
                code = req.code, language = req.language,
            )
        )
        val text = postJsonWithResponse(baseUrl, "/api/code/submit", body, getAuthHeader())
        val resp = json.decodeFromString<SubmitCodeResponseDto>(text)
        SubmissionResult(
            response = toTestResults(resp.testcases, resp.compileOutput, submitSummary(resp)),
            message = resp.message,
        )
    } catch (e: Exception) {
        SubmissionResult(response = errorResults(e), message = e.message ?: "Submit failed")
    }

    override suspend fun lastRuntimeError(): RuntimeError = RuntimeError(status = "ERROR", stderr = "")

    private suspend fun postRun(
        courseId: String, section: Int, labNumber: Int, problemName: String,
        studentEmail: String, language: String, code: String, stdin: String,
    ): RunCodeResponseDto {
        val body = json.encodeToString(
            RunCodeRequestDto(
                courseId = courseId, section = section, labNumber = labNumber,
                problemName = problemName, studentEmail = studentEmail,
                code = code, language = language, stdin = stdin.ifBlank { null },
            )
        )
        val text = postJsonWithResponse(baseUrl, "/api/code/run", body, getAuthHeader())
        return json.decodeFromString(text)
    }

    private fun toTestResults(cases: List<TestcaseResultDto>?, compileOutput: String?, status: String): TestResultsResponse {
        val rows = if (compileOutput != null) {
            listOf(TestResult(testCase = 1, input = "", expectedOutput = "", actualOutput = compileOutput, passed = false))
        } else {
            cases.orEmpty().mapIndexed { i, tc ->
                TestResult(
                    testCase = i + 1,
                    input = tc.input ?: "",
                    expectedOutput = tc.expected ?: "",
                    actualOutput = tc.stdout ?: "",
                    passed = tc.status == "AC" || tc.status == null,
                )
            }
        }
        return TestResultsResponse(status = status, results = rows)
    }

    private fun runSummary(resp: RunCodeResponseDto): String = when {
        resp.compileOutput != null -> "Compile error"
        resp.testcases.isNullOrEmpty() -> resp.message
        resp.testcases.all { it.status == "AC" || it.status == null } -> "All tests passed"
        else -> "Some tests failed"
    }

    private fun submitSummary(resp: SubmitCodeResponseDto): String = when {
        resp.compileOutput != null -> "Compile error"
        resp.status != null -> "Submitted: ${resp.status}" + if (resp.total != null) " (${resp.passed ?: 0}/${resp.total} passed)" else ""
        else -> resp.message
    }

    private fun errorResults(e: Exception): TestResultsResponse =
        TestResultsResponse(status = "Error: ${e.message}", results = emptyList())
}
