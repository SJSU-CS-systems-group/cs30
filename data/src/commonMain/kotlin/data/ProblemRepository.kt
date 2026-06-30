package data

interface ProblemRepository {
    suspend fun listProblemsForStudent(): List<LabProblemInfo>
    suspend fun getProblemContent(courseId: String, section: Int, labNumber: Int, slug: String): ProblemContent
}
