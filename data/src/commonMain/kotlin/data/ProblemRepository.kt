package data

interface ProblemRepository {
    suspend fun listProblemsForStudent(): List<LabProblemInfo>
    suspend fun getProblemHtml(courseId: String, section: Int, labNumber: Int, slug: String): String
    suspend fun getProblemCss(courseId: String, section: Int, labNumber: Int, slug: String): String

    // Mock data methods (for testing)
    suspend fun getRunOutput(): RunOutput
    suspend fun getTestResults(): TestResultsResponse
    suspend fun getRuntimeError(): RuntimeError
}
