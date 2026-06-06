package editor

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import backend.postJsonAuth

class HttpAutosaveService(
    private val baseUrl: String,
    private val authHeader: String?,
    private val problemSlug: String,
) : AutosaveService {

    override suspend fun save(code: String, language: String) {
        if (baseUrl.isEmpty()) return
        val body = Json.encodeToString(buildJsonObject {
            put("problemSlug", problemSlug)
            put("code", code)
            put("language", language)
        })
        postJsonAuth(baseUrl, "/api/autosave", body, authHeader)
        println("[Autosave] posted $problemSlug")
    }
}
