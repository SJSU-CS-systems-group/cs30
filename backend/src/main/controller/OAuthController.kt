package com.cs30.server.controller

import com.cs30.server.models.GoogleTokenResponse
import com.cs30.server.models.GoogleUserInfo
import com.cs30.server.service.ApiTokenStore
import jakarta.servlet.http.HttpSession
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.*
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.bind.annotation.*
import org.springframework.web.client.RestTemplate
import java.net.URLEncoder

@RestController
class OAuthController(
    @Value("\${google.client-id}") private val clientId: String,
    @Value("\${google.client-secret}") private val clientSecret: String,
    @Value("\${google.redirect-uri:http://localhost:8080/callback}") private val redirectUri: String,
    private val tokenStore: ApiTokenStore,
) {
    private val restTemplate = RestTemplate()

    @GetMapping("/login")
    fun login(
        @RequestParam("app_callback", required = false) appCallback: String?,
        @RequestParam("state", required = false) state: String?,
        session: HttpSession
    ): ResponseEntity<Void> {
        if (appCallback != null) {
            session.setAttribute("pending_app_callback", appCallback)
        }
        if (state != null) {
            session.setAttribute("pending_state", state)
        }
        val googleAuthUrl = "https://accounts.google.com/o/oauth2/v2/auth?" +
            "client_id=$clientId&" +
            "redirect_uri=${URLEncoder.encode(redirectUri, "UTF-8")}&" +
            "response_type=code&" +
            "scope=openid%20email%20profile&" +
            "hd=sjsu.edu&" +
            "prompt=select_account"
        return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, googleAuthUrl)
            .build()
    }

    @GetMapping("/callback")
    fun callback(
        @RequestParam("code", required = false) code: String?,
        session: HttpSession
    ): ResponseEntity<Void> {
        if (code == null) {
            val appCallback = session.getAttribute("pending_app_callback") as? String
            val state = session.getAttribute("pending_state") as? String
            val stateParam = if (state != null) "&state=${URLEncoder.encode(state, "UTF-8")}" else ""
            val destination = appCallback ?: "/"
            return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, "$destination?error=no_code$stateParam")
                .build()
        }

        try {
            // Exchange code for token
            val tokenRequest = LinkedMultiValueMap<String, String>().apply {
                add("code", code)
                add("client_id", clientId)
                add("client_secret", clientSecret)
                add("redirect_uri", redirectUri)
                add("grant_type", "authorization_code")
            }
            val tokenHeaders = HttpHeaders().apply {
                contentType = MediaType.APPLICATION_FORM_URLENCODED
            }
            val tokenResponse = restTemplate.postForObject(
                "https://oauth2.googleapis.com/token",
                HttpEntity(tokenRequest, tokenHeaders),
                GoogleTokenResponse::class.java
            )!!

            // Get user info
            val userHeaders = HttpHeaders().apply {
                setBearerAuth(tokenResponse.accessToken)
            }
            val userInfo = restTemplate.exchange(
                "https://www.googleapis.com/oauth2/v2/userinfo",
                HttpMethod.GET,
                HttpEntity<Any>(userHeaders),
                GoogleUserInfo::class.java
            ).body!!

            // Store in session
            session.setAttribute("user_email", userInfo.email)
            session.setAttribute("user_name", userInfo.name)

            // Generate API token for desktop client
            val apiToken = tokenStore.generate(userInfo.email)

            // Handle redirect
            val appCallback = session.getAttribute("pending_app_callback") as? String
            val state = session.getAttribute("pending_state") as? String
            session.removeAttribute("pending_app_callback")
            session.removeAttribute("pending_state")

            val nameParam = URLEncoder.encode(userInfo.name, "UTF-8")
            val emailParam = URLEncoder.encode(userInfo.email, "UTF-8")
            val tokenParam = URLEncoder.encode(apiToken, "UTF-8")
            val stateParam = if (state != null) "&state=${URLEncoder.encode(state, "UTF-8")}" else ""
            val destination = appCallback ?: "/"

            return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, "$destination?name=$nameParam&email=$emailParam&api_token=$tokenParam$stateParam")
                .build()
        } catch (e: Exception) {
            // OAuth exchange failed
            val appCallback = session.getAttribute("pending_app_callback") as? String
            val state = session.getAttribute("pending_state") as? String
            session.removeAttribute("pending_app_callback")
            session.removeAttribute("pending_state")

            val stateParam = if (state != null) "&state=${URLEncoder.encode(state, "UTF-8")}" else ""
            val destination = appCallback ?: "/"
            return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, "$destination?error=auth_failed$stateParam")
                .build()
        }
    }

    @GetMapping("/logout")
    fun logout(session: HttpSession): ResponseEntity<Void> {
        (session.getAttribute("user_email") as? String)?.let { tokenStore.revokeByEmail(it) }
        session.invalidate()
        return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, "/")
            .build()
    }
}
