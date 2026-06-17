package editor

import data.LabProblemInfo

interface AutosaveService {
    suspend fun save(code: String, language: String)

    /** Latest autosaved code for this problem, or null if none exists. */
    suspend fun loadLatest(): String?
}

object NoOpAutosaveService : AutosaveService {
    override suspend fun save(code: String, language: String) = Unit
    override suspend fun loadLatest(): String? = null
}

expect fun createAutosaveService(
    baseUrl: String,
    problem: LabProblemInfo,
): AutosaveService
