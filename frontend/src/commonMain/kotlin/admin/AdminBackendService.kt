package admin

import data.AdminCliTokenInfo

interface AdminBackendService {
    suspend fun listCliTokens(): List<AdminCliTokenInfo>
    suspend fun deleteCliToken(id: String): Boolean
    suspend fun logout()
}
