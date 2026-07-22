package backend

import data.RunOutput
import data.RuntimeError
import data.SubmissionInfo
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
        val resp = postRun(req.courseId, req.section, req.labNumber, req.problemName, req.studentEmail, req.language, req.code, req.customStdins)
        val tc = resp.testcases?.firstOrNull()
        return RunOutput(
            status = if (resp.compileOutput != null) "ERROR" else "SUCCESS",
            stdout = tc?.stdout ?: "",
            stderr = resp.compileOutput ?: tc?.stderr ?: "",
            executionTimeMs = ((tc?.timeS ?: 0.0) * 1000).toInt(),
        )
    }

    override suspend fun testCode(req: TestRequest): TestResultsResponse = try {
        val resp = postRun(req.courseId, req.section, req.labNumber, req.problemName, req.studentEmail, req.language, req.code, req.customStdins)
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
        println("[HttpBackendService] submitCode failed: ${e.message}")
        SubmissionResult(response = errorResults(e), message = "Submit failed")
    }

    override suspend fun lastRuntimeError(): RuntimeError = RuntimeError(status = "ERROR", stderr = "")

    override suspend fun listSubmissions(req: SubmissionsRequest): List<SubmissionInfo> = try {
        val url = "$baseUrl/api/code/submissions?courseId=${req.courseId}&section=${req.section}&labNumber=${req.labNumber}&problemName=${req.problemName}&studentEmail=${req.studentEmail}"
        val text = getJsonWithResponse(url, getAuthHeader())
        json.decodeFromString(text)
    } catch (e: Exception) {
        println("[HttpBackendService] listSubmissions failed: ${e.message}")
        emptyList()
    }

    override suspend fun queueStatus(): QueueStatus {
        val text = getJsonWithResponse("$baseUrl/api/code/queue-status", getAuthHeader())
        val dto = json.decodeFromString<QueueStatusResponseDto>(text)
        return QueueStatus(inFlight = dto.inFlight, maxQueueSize = dto.maxQueueSize, maxWorkers = dto.maxWorkers)
    }

    private suspend fun postRun(
        courseId: String, section: Int, labNumber: Int, problemName: String,
        studentEmail: String, language: String, code: String, customStdins: List<String>,
    ): RunCodeResponseDto {
        val body = json.encodeToString(
            RunCodeRequestDto(
                courseId = courseId, section = section, labNumber = labNumber,
                problemName = problemName, studentEmail = studentEmail,
                code = code, language = language, customStdins = customStdins,
            )
        )
        val text = postJsonWithResponse(baseUrl, "/api/code/run", body, getAuthHeader())
        return json.decodeFromString(text)
    }

    private fun toTestResults(cases: List<TestcaseResultDto>?, compileOutput: String?, status: String): TestResultsResponse {
        val rows = if (compileOutput != null) {
            listOf(TestResult(testCase = 1, input = "", expectedOutput = "", actualOutput = compileOutput, passed = false, status = "CE"))
        } else {
            // Display order: custom cases first, then sample, then hidden (secret).
            cases.orEmpty().sortedBy { caseRank(it.name) }.mapIndexed { i, tc ->
                TestResult(
                    testCase = i + 1,
                    input = tc.input ?: "",
                    expectedOutput = tc.expected ?: "",
                    actualOutput = tc.stdout ?: "",
                    passed = tc.status == "AC" || tc.status == null,
                    status = tc.status,
                    hidden = tc.name.startsWith("secret"),
                    executionTimeMs = tc.timeS?.let { (it * 1000).toInt() },
                    stderr = tc.stderr ?: "",
                )
            }
        }
        return TestResultsResponse(status = status, results = rows)
    }

    private fun caseRank(name: String): Int = when {
        name.startsWith("custom") -> 0
        name.startsWith("sample") -> 1
        name.startsWith("secret") -> 2
        else -> 3
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

    private fun errorResults(e: Exception): TestResultsResponse {
        println("[HttpBackendService] request failed: ${e.message}")
        return TestResultsResponse(status = "Error: Unable to run code", results = emptyList())
    }
}
