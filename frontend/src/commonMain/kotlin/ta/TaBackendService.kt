package ta

import data.*

interface TaBackendService {
    suspend fun getSections(): List<TaSectionInfo>
    suspend fun getActiveSessions(): List<TaSessionInfo>
    suspend fun getStats(): TaDashboardStats
    suspend fun getLabs(): List<TaLabInfo>
    suspend fun getLabStudents(labId: String): List<TaSessionInfo>
    suspend fun getLabHealth(labId: String): TaLabHealthReport
    suspend fun kickStudent(token: String): Boolean
    suspend fun logout()
    suspend fun checkSession(): TaCheckSessionResponse

    /**
     * Reveals this TA's own CLI token - gets or creates it on first call, or (with reset=true)
     * invalidates the existing one and mints a fresh one. Null once it's no longer recoverable.
     */
    suspend fun getCliToken(reset: Boolean = false): String?
    suspend fun getActivityLog(courseId: String, studentEmail: String, sinceMs: Long = 0): List<TaActivityLogEntry>
}
