package editor

import data.LabProblemInfo

interface AutosaveService {
    /** Saves the code. Returns false if the session is gone (HTTP 401) so the caller can stop autosaving. */
    suspend fun save(code: String, language: String): Boolean

    /** Latest autosaved code for this problem, or null if none exists. */
    suspend fun loadLatest(): String?
}

object NoOpAutosaveService : AutosaveService {
    override suspend fun save(code: String, language: String): Boolean = true
    override suspend fun loadLatest(): String? = null
}

expect fun createAutosaveService(
    baseUrl: String,
    problem: LabProblemInfo,
): AutosaveService
