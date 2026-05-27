package labx.auth

import labx.data.AuthResult
import labx.data.Student

interface AuthService {
    suspend fun login(): AuthResult
    suspend fun logout()
    fun currentUser(): Student?
}
