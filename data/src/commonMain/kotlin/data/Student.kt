package data

import kotlinx.serialization.Serializable

@Serializable
data class Student(
    val id: String,
    val name: String,
    val email: String,
    /** Set from the `role=ta` login param: the course TA using the student app in practice mode. Informational only — the backend re-derives the role on every request. */
    val isTa: Boolean = false
)

data class AuthResult(
    val success: Boolean,
    val student: Student?,
    val errorMessage: String? = null
)
