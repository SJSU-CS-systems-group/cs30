package backend

import kotlinx.serialization.json.Json
import data.LabProblemInfo
import data.MockDataRepository
import data.ProblemRepository
import data.RunOutput
import data.RuntimeError
import data.TestResultsResponse

class HttpProblemRepository(
    private val baseUrl: String,
    private val getAuthHeader: () -> String? = { null }
) : ProblemRepository {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun listProblemsForStudent(): List<LabProblemInfo> {
        val response = getJson(baseUrl, "/api/problems/me", getAuthHeader())
        return json.decodeFromString(response)
    }

    override suspend fun getProblemHtml(courseId: String, section: Int, labNumber: Int, slug: String): String {
        return getJson(baseUrl, "/api/problems/$courseId/section/$section/lab/$labNumber/$slug", getAuthHeader())
    }

    override suspend fun getProblemCss(courseId: String, section: Int, labNumber: Int, slug: String): String {
        return getJson(baseUrl, "/api/problems/$courseId/section/$section/lab/$labNumber/$slug/css", getAuthHeader())
    }

    // Mock responses — still served from bundled resources
    override suspend fun getRunOutput(): RunOutput = MockDataRepository.getRunOutput()
    override suspend fun getTestResults(): TestResultsResponse = MockDataRepository.getTestResults()
    override suspend fun getRuntimeError(): RuntimeError = MockDataRepository.getRuntimeError()
}
