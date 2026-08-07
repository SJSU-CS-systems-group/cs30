package data

import kotlinx.serialization.Serializable

@Serializable
data class AdminUser(
    val email: String,
    val name: String,
    val sessionToken: String,
    // Populated client-side after login via AdminBackendService.getCliToken(), never from the
    // OAuth redirect itself (see AdminOAuthController) - only present right after the CLI admin
    // token was just (re)generated, since only a hash is stored server-side past that point.
    val token: String? = null
)

@Serializable
data class AdminCliTokenInfo(
    val id: String,
    val email: String,
    val role: String
)

@Serializable
data class AdminCheckSessionResponse(
    val hasActiveSession: Boolean,
    val email: String? = null
)
