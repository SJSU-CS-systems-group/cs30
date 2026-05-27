package labx.data

interface ProblemRepository {
    suspend fun getProblemHtml(): String
    suspend fun getRunOutput(): RunOutput
    suspend fun getTestResults(): TestResultsResponse
    suspend fun getRuntimeError(): RuntimeError
}
