package labx.backend

import labx.data.MockDataRepository
import labx.data.RunOutput
import labx.data.RuntimeError
import labx.data.TestResultsResponse

data class RunRequest(val language: String, val code: String, val stdin: String)
data class TestRequest(val language: String, val code: String, val stdin: String)
data class SubmitRequest(val language: String, val code: String)

data class SubmissionResult(
    val response: TestResultsResponse,
    val message: String
)

interface BackendService {
    suspend fun runCode(req: RunRequest): RunOutput
    suspend fun testCode(req: TestRequest): TestResultsResponse
    suspend fun submitCode(req: SubmitRequest): SubmissionResult
    suspend fun lastRuntimeError(): RuntimeError
}

// TODO(real-backend): replace with HttpBackendService that POSTs to
// /run, /test, /submit on go-judge; keep this dummy for offline/dev mode.
class DummyBackendService : BackendService {

    override suspend fun runCode(req: RunRequest): RunOutput {
        log("runCode", "lang=${req.language} codeLen=${req.code.length} stdinLen=${req.stdin.length}")
        // TODO(real-backend): POST req to /run, parse RunOutput from response body.
        return MockDataRepository.getRunOutput()
    }

    override suspend fun testCode(req: TestRequest): TestResultsResponse {
        log("testCode", "lang=${req.language} codeLen=${req.code.length} stdinLen=${req.stdin.length}")
        // TODO(real-backend): POST req to /test, parse TestResultsResponse.
        return MockDataRepository.getTestResults()
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

    private fun log(action: String, detail: String) {
        println("[DummyBackendService] $action :: $detail")
    }
}
