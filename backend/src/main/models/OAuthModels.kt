package com.cs30.server.models

import com.fasterxml.jackson.annotation.JsonProperty

data class GoogleTokenResponse(
    @JsonProperty("access_token")
    val accessToken: String,
    @JsonProperty("token_type")
    val tokenType: String,
    @JsonProperty("expires_in")
    val expiresIn: Int,
    @JsonProperty("id_token")
    val idToken: String? = null
)

data class GoogleUserInfo(
    val email: String,
    val name: String = "",
    val picture: String = ""
)
