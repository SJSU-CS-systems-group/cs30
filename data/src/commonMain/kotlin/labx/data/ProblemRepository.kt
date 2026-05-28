package labx.data

interface ProblemRepository {
    suspend fun listProblems(): List<ProblemSummary>
    suspend fun getProblemHtml(slug: String): String
    suspend fun getRunOutput(): RunOutput
    suspend fun getTestResults(): TestResultsResponse
    suspend fun getRuntimeError(): RuntimeError
}
