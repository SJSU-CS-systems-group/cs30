package labx.backend

import kotlinx.serialization.json.Json
import labx.data.MockDataRepository
import labx.data.ProblemRepository
import labx.data.ProblemSummary
import labx.data.RunOutput
import labx.data.RuntimeError
import labx.data.TestResultsResponse

class HttpProblemRepository(private val baseUrl: String) : ProblemRepository {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun listProblems(): List<ProblemSummary> {
        val response = getJson(baseUrl, "/api/problems")
        return json.decodeFromString(response)
    }

    override suspend fun getProblemHtml(slug: String): String {
        return getJson(baseUrl, "/api/problems/$slug")
    }

    override suspend fun getProblemCss(): String {
        return getJson(baseUrl, "/api/problems/css")
    }

    // Mock responses — still served from bundled resources
    override suspend fun getRunOutput(): RunOutput = MockDataRepository.getRunOutput()
    override suspend fun getTestResults(): TestResultsResponse = MockDataRepository.getTestResults()
    override suspend fun getRuntimeError(): RuntimeError = MockDataRepository.getRuntimeError()
}
