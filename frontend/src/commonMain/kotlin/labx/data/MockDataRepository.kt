@file:OptIn(org.jetbrains.compose.resources.ExperimentalResourceApi::class)

package labx.data

import cs30.frontend.generated.resources.Res
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

object MockDataRepository : ProblemRepository {

    suspend fun getStudent(): Student {
        val text = Res.readBytes("files/student.json").decodeToString()
        return json.decodeFromString(text)
    }

    override suspend fun getProblemHtml(): String =
        Res.readBytes("files/problem.html").decodeToString()

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
