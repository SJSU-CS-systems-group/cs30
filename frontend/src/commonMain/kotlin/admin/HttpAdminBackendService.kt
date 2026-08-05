package admin

import backend.deleteWithAuth
import backend.getJsonWithResponse
import backend.postJsonAuth
import backend.postJsonWithResponse
import data.AdminCheckSessionResponse
import data.AdminCliTokenInfo
import data.CliTokenReveal
import kotlinx.serialization.json.Json

class HttpAdminBackendService(
    private val baseUrl: String,
    private val authHeader: () -> String?
) : AdminBackendService {

    private val json = Json { ignoreUnknownKeys = true }

    // The admin token itself never appears here - the backend excludes it from /api/admin/cli-tokens
    // entirely (see AdminController), since it's not something this page should show or delete.
    override suspend fun listCliTokens(): List<AdminCliTokenInfo> {
        val response = getJsonWithResponse("$baseUrl/api/admin/cli-tokens", authHeader())
        return json.decodeFromString(response)
    }

    override suspend fun deleteCliToken(id: String): Boolean {
        val status = deleteWithAuth("$baseUrl/api/admin/cli-tokens/$id", authHeader())
        return status in 200..299
    }

    override suspend fun getCliToken(reset: Boolean): String? {
        val path = if (reset) "/api/admin/cli-token?reset=true" else "/api/admin/cli-token"
        val response = postJsonWithResponse(baseUrl, path, "", authHeader())
        return json.decodeFromString<CliTokenReveal>(response).token
    }

    override suspend fun logout() {
        postJsonAuth(baseUrl, "/api/admin/logout", "", authHeader())
    }

    override suspend fun checkSession(): AdminCheckSessionResponse {
        val response = getJsonWithResponse("$baseUrl/api/admin/check-session", authHeader())
        return json.decodeFromString(response)
    }
}
