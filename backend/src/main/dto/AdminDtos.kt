package com.cs30.server.dto

data class AdminCliTokenInfo(
    val id: String,
    val email: String,
    val role: String
)

data class AdminCheckSessionResponse(
    val hasActiveSession: Boolean,
    val email: String?
)
