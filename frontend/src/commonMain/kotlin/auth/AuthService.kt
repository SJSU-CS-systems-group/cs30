package auth

import data.AuthResult
import data.Student

interface AuthService {
    suspend fun login(): AuthResult
    suspend fun logout()
    fun currentUser(): Student?
}
