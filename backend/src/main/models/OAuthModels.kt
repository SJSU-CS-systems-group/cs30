package com.cs30.server.models

data class GoogleTokenResponse(
    val access_token: String,
    val token_type: String,
    val expires_in: Int,
    val id_token: String? = null
)

data class GoogleUserInfo(
    val email: String,
    val name: String = "",
    val picture: String = ""
)
