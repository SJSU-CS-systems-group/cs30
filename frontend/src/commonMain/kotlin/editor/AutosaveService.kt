package editor

interface AutosaveService {
    suspend fun save(code: String, language: String)
}

object NoOpAutosaveService : AutosaveService {
    override suspend fun save(code: String, language: String) = Unit
}

expect fun createAutosaveService(
    baseUrl: String,
    problemSlug: String,
): AutosaveService
