package data

import kotlinx.serialization.Serializable

@Serializable
data class Student(
    val id: String,
    val name: String,
    val email: String
)

data class AuthResult(
    val success: Boolean,
    val student: Student?,
    val errorMessage: String? = null
)
