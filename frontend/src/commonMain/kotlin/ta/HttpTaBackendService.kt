package ta

import backend.deleteWithAuth
import backend.getJsonWithResponse
import backend.postJsonAuth
import backend.postJsonWithResponse
import data.*
import kotlinx.serialization.json.Json

class HttpTaBackendService(
    private val baseUrl: String,
    private val authHeader: () -> String?
) : TaBackendService {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getSections(): List<TaSectionInfo> {
        val response = getJsonWithResponse("$baseUrl/api/ta/sections", authHeader())
        return json.decodeFromString<List<TaSectionInfo>>(response)
    }

    override suspend fun getActiveSessions(): List<TaSessionInfo> {
        val response = getJsonWithResponse("$baseUrl/api/ta/sessions", authHeader())
        return json.decodeFromString<List<TaSessionInfo>>(response)
    }

    override suspend fun getStats(): TaDashboardStats {
        val response = getJsonWithResponse("$baseUrl/api/ta/stats", authHeader())
        return json.decodeFromString<TaDashboardStats>(response)
    }

    override suspend fun getLabs(): List<TaLabInfo> {
        val response = getJsonWithResponse("$baseUrl/api/ta/labs", authHeader())
        return json.decodeFromString<List<TaLabInfo>>(response)
    }

    override suspend fun getLabStudents(labId: String): List<TaSessionInfo> {
        val response = getJsonWithResponse("$baseUrl/api/ta/labs/$labId/students", authHeader())
        return json.decodeFromString<List<TaSessionInfo>>(response)
    }

    override suspend fun getLabHealth(labId: String): TaLabHealthReport {
        val response = getJsonWithResponse("$baseUrl/api/ta/labs/$labId/health", authHeader())
        return json.decodeFromString<TaLabHealthReport>(response)
    }

    override suspend fun kickStudent(token: String): Boolean {
        val status = deleteWithAuth("$baseUrl/api/ta/sessions/$token", authHeader())
        return status in 200..299
    }

    override suspend fun logout() {
        postJsonAuth(baseUrl, "/api/ta/logout", "", authHeader())
    }

    override suspend fun checkSession(): TaCheckSessionResponse {
        val response = getJsonWithResponse("$baseUrl/api/ta/check-session", authHeader())
        return json.decodeFromString<TaCheckSessionResponse>(response)
    }

    override suspend fun getActivityLog(courseId: String, studentEmail: String, sinceMs: Long): List<TaActivityLogEntry> {
        val response = getJsonWithResponse("$baseUrl/api/ta/activity/$courseId/$studentEmail?sinceMs=$sinceMs", authHeader())
        return json.decodeFromString<List<TaActivityLogEntry>>(response)
    }

    override suspend fun getCliToken(reset: Boolean): String? {
        val path = if (reset) "/api/ta/cli-token?reset=true" else "/api/ta/cli-token"
        val response = postJsonWithResponse(baseUrl, path, "", authHeader())
        return json.decodeFromString<CliTokenReveal>(response).token
    }
}
