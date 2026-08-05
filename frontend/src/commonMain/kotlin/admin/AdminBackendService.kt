package admin

import data.AdminCheckSessionResponse
import data.AdminCliTokenInfo

interface AdminBackendService {
    suspend fun listCliTokens(): List<AdminCliTokenInfo>
    suspend fun deleteCliToken(id: String): Boolean

    /**
     * Reveals this admin's own CLI token - gets or creates it on first call, or (with reset=true)
     * invalidates the existing one and mints a fresh one. Null once it's no longer recoverable.
     */
    suspend fun getCliToken(reset: Boolean = false): String?

    suspend fun logout()

    /** Heartbeat - same shape/cadence as TaBackendService.checkSession. */
    suspend fun checkSession(): AdminCheckSessionResponse
}
