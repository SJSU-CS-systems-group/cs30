package backend

import kotlinx.serialization.json.Json
import data.LabProblemInfo
import data.ProblemContent
import data.ProblemRepository

class HttpProblemRepository(
    private val baseUrl: String,
    private val getAuthHeader: () -> String? = { null }
) : ProblemRepository {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun listProblemsForStudent(): List<LabProblemInfo> {
        val response = getJson(baseUrl, "/api/problems/lab", getAuthHeader())
        return json.decodeFromString(response)
    }

    override suspend fun getProblemContent(courseId: String, section: Int, labNumber: Int, slug: String): ProblemContent {
        val response = getJson(baseUrl, "/api/problems/$courseId/section/$section/lab/$labNumber/$slug", getAuthHeader())
        return json.decodeFromString(response)
    }

}
