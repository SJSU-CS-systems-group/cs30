package data

interface ProblemRepository {
    suspend fun listProblemsForStudent(): List<LabProblemInfo>
    suspend fun getProblemContent(courseId: String, section: Int, labNumber: Int, slug: String): ProblemContent

    // Mock data methods (for testing)
    suspend fun getRunOutput(): RunOutput
    suspend fun getTestResults(): TestResultsResponse
    suspend fun getRuntimeError(): RuntimeError
}
