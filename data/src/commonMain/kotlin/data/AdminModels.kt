package data

import kotlinx.serialization.Serializable

@Serializable
data class AdminUser(
    val email: String,
    val name: String,
    val sessionToken: String,
    // Only present right after the CLI admin token was just (re)generated - only a hash is
    // stored server-side, so it can never be included again on later logins.
    val token: String? = null
)

@Serializable
data class AdminCliTokenInfo(
    val id: String,
    val email: String,
    val role: String
)
