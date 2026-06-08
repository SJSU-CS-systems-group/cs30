@file:OptIn(org.jetbrains.compose.resources.ExperimentalResourceApi::class)

package data

import cs30.frontend.generated.resources.Res
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

object MockDataRepository : ProblemRepository {

    suspend fun getStudent(): Student {
        val text = Res.readBytes("files/student.json").decodeToString()
        return json.decodeFromString(text)
    }

    override suspend fun listProblemsForStudent(): List<LabProblemInfo> = ProblemCatalog.problems

    override suspend fun getProblemHtml(courseId: String, section: Int, labNumber: Int, slug: String): String =
        Res.readBytes("files/problems/$slug/index.html").decodeToString()

    override suspend fun getProblemCss(courseId: String, section: Int, labNumber: Int, slug: String): String =
        Res.readBytes("files/problem.css").decodeToString()

    override suspend fun getRunOutput(): RunOutput {
        val text = Res.readBytes("files/run-output.json").decodeToString()
        return json.decodeFromString(text)
    }

    override suspend fun getTestResults(): TestResultsResponse {
        val text = Res.readBytes("files/test-results.json").decodeToString()
        return json.decodeFromString(text)
    }

    override suspend fun getRuntimeError(): RuntimeError {
        val text = Res.readBytes("files/runtime-error.json").decodeToString()
        return json.decodeFromString(text)
    }
}
