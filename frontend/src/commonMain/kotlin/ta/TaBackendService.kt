package ta

import data.*

interface TaBackendService {
    suspend fun getSections(): List<TaSectionInfo>
    suspend fun getActiveSessions(): List<TaSessionInfo>
    suspend fun getStats(): TaDashboardStats
    suspend fun getLabs(): List<TaLabInfo>
    suspend fun getLabStudents(labId: String): List<TaSessionInfo>
    suspend fun kickStudent(token: String): Boolean
    suspend fun logout()
    suspend fun checkSession(): TaCheckSessionResponse
    suspend fun getActivityLog(courseId: String, studentEmail: String): List<TaActivityLogEntry>
}
