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
    suspend fun getActivityLog(courseId: String, studentEmail: String, sinceMs: Long = 0): List<TaActivityLogEntry>
}
