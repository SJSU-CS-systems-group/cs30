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
    val message: String,
    // Raw judge verdict code (AC/WA/TLE/RTE/MLE/CE/JE), kept separate from response.status,
    // which is a human-readable summary sentence. Null when the judge couldn't be reached.
    val verdict: String? = null,
)

data class SubmissionsRequest(
    val courseId: String,
    val section: Int,
    val labNumber: Int,
    val problemName: String,
    val studentEmail: String
)

// A stateless, system-wide load snapshot from the judge — not a per-student/per-request position.
// See CodeEditorState's onSubmit()/onTest() for exactly when this is (and isn't) fetched.
data class QueueStatus(
    val inFlight: Int,
    val maxQueueSize: Int,
    val maxWorkers: Int,
)

interface BackendService {
    suspend fun runCode(req: RunRequest): RunOutput
    suspend fun testCode(req: TestRequest): TestResultsResponse
    suspend fun submitCode(req: SubmitRequest): SubmissionResult
    suspend fun lastRuntimeError(): RuntimeError
    suspend fun listSubmissions(req: SubmissionsRequest): List<SubmissionInfo>
    suspend fun queueStatus(): QueueStatus
}
