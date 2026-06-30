package backend

import data.MockDataRepository
import data.RunOutput
import data.RuntimeError
import data.SubmissionInfo
import data.TestResult
import data.TestResultsResponse

data class RunRequest(
    val courseId: String, val section: Int, val labNumber: Int,
    val problemName: String, val studentEmail: String,
    val language: String, val code: String, val customStdins: List<String>,
)
data class TestRequest(
    val courseId: String, val section: Int, val labNumber: Int,
    val problemName: String, val studentEmail: String,
    val language: String, val code: String, val customStdins: List<String>,
)
data class SubmitRequest(
    val courseId: String, val section: Int, val labNumber: Int,
    val problemName: String, val studentEmail: String,
    val language: String, val code: String,
)

data class SubmissionResult(
    val response: TestResultsResponse,
    val message: String
)

data class SubmissionsRequest(
    val courseId: String,
    val section: Int,
    val labNumber: Int,
    val problemName: String,
    val studentEmail: String
)

interface BackendService {
    suspend fun runCode(req: RunRequest): RunOutput
    suspend fun testCode(req: TestRequest): TestResultsResponse
    suspend fun submitCode(req: SubmitRequest): SubmissionResult
    suspend fun lastRuntimeError(): RuntimeError
    suspend fun listSubmissions(req: SubmissionsRequest): List<SubmissionInfo>
}

// TODO(real-backend): replace with HttpBackendService that POSTs to
// /run, /test, /submit on go-judge; keep this dummy for offline/dev mode.
class DummyBackendService : BackendService {

    override suspend fun runCode(req: RunRequest): RunOutput {
        log("runCode", "lang=${req.language} codeLen=${req.code.length} customCases=${req.customStdins.size}")
        // TODO(real-backend): POST req to /run, parse RunOutput from response body.
        return MockDataRepository.getRunOutput()
    }

    override suspend fun testCode(req: TestRequest): TestResultsResponse {
        val base = MockDataRepository.getTestResults()
        val firstCustom = req.customStdins.firstOrNull()
        val withCustom = if (firstCustom == null) base else base.copy(
            results = base.results + TestResult(
                testCase = base.results.size + 1,
                input = firstCustom,
                expectedOutput = "(custom)",
                actualOutput = "(mock run)",
                passed = true,
            )
        )
        log("testCode", "lang=${req.language} codeLen=${req.code.length} customCases=${req.customStdins.size}")
        return withCustom
    }

    override suspend fun submitCode(req: SubmitRequest): SubmissionResult {
        log("submitCode", "lang=${req.language} codeLen=${req.code.length}")
        // TODO(real-backend): POST req to /submit; persist; return server message.
        return SubmissionResult(
            response = MockDataRepository.getTestResults(),
            message = "Submission saved locally for prototype."
        )
    }

    override suspend fun lastRuntimeError(): RuntimeError {
        log("lastRuntimeError", "(mock)")
        // TODO(real-backend): server attaches runtime error to the run/test response;
        // this getter exists only so the prototype can demo the Error view.
        return MockDataRepository.getRuntimeError()
    }

    override suspend fun listSubmissions(req: SubmissionsRequest): List<SubmissionInfo> {
        log("listSubmissions", "problem=${req.problemName} student=${req.studentEmail}")
        return listOf(
            SubmissionInfo("2024-01-15 10:30:00", 5, 5, 123.0, "AC", "/path/to/submission1.kt", "fun main() {\n    println(\"Hello, World!\")\n}"),
            SubmissionInfo("2024-01-15 10:25:00", 3, 5, 456.0, "WA", "/path/to/submission2.kt", "fun main() {\n    // Wrong answer\n}"),
            SubmissionInfo("2024-01-15 10:20:00", 0, 5, null, "CE", "/path/to/submission3.kt", "fun main( // compile error"),
        )
    }

    private fun log(action: String, detail: String) {
        println("[DummyBackendService] $action :: $detail")
    }
}
