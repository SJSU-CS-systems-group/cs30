package editor

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import backend.postJsonAuth
import backend.getJson
import data.LabProblemInfo

class HttpAutosaveService(
    private val baseUrl: String,
    private val authHeader: String?,
    private val problem: LabProblemInfo,
) : AutosaveService {

    override suspend fun save(code: String, language: String) {
        println("[Autosave] save slug=${problem.slug} section=${problem.section} lab=${problem.labNumber} codeLen=${code.length}")
        val body = Json.encodeToString(buildJsonObject {
            put("courseId", problem.courseId)
            put("section", problem.section)
            put("labNumber", problem.labNumber)
            put("problemSlug", problem.slug)
            put("code", code)
            put("language", language)
        })
        postJsonAuth(baseUrl, "/api/autosave", body, authHeader)
        println("[Autosave] posted ${problem.slug}")
    }

    override suspend fun loadLatest(): String? {
        val path = "/api/autosave/${problem.courseId}/${problem.section}/${problem.labNumber}/${problem.slug}"
        return try {
            getJson(baseUrl, path, authHeader).ifBlank { null }
                .also { println("[Autosave] loadLatest slug=${problem.slug} found=${it != null}") }
        } catch (e: Exception) {
            println("[Autosave] loadLatest failed: ${e.message}")
            null
        }
    }
}
