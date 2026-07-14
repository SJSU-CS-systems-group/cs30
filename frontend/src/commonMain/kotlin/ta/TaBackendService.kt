package ta

import data.*

interface TaBackendService {
    suspend fun getSections(): List<TaSectionInfo>
    suspend fun getActiveSessions(): List<TaSessionInfo>
    suspend fun getStats(): TaDashboardStats
    suspend fun kickStudent(token: String): Boolean
    suspend fun logout()
    suspend fun checkSession(): TaCheckSessionResponse
}
