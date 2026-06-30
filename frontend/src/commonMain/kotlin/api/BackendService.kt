package backend

import data.RunOutput
import data.RuntimeError
import data.SubmissionInfo
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
