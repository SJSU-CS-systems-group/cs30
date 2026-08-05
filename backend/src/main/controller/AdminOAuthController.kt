package com.cs30.server.controller

import com.cs30.server.models.GoogleTokenResponse
import com.cs30.server.models.GoogleUserInfo
import com.cs30.server.service.AdminIdentityService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.*
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.bind.annotation.*
import org.springframework.web.client.RestTemplate
import java.net.URLEncoder

/**
 * OAuth controller for the admin webpage.
 * Like TaOAuthController but:
 * - Uses /admin/login and /admin/callback endpoints
 * - Verifies email against the single admin-email allowlist entry (not enrollment/taEmail)
 * - Every successful login issues an AdminSession (separate from CliToken - this one authenticates
 *   the *page*, not the CLI) so the admin lands in a real dashboard rather than a one-shot reveal
 * - The CLI admin token itself is never included here (in the URL or otherwise) - once the
 *   dashboard has a valid AdminSession, it fetches/reveals/resets the CLI token itself via
 *   AdminController's POST /api/admin/cli-token, authenticated by that session
 */
@RestController
class AdminOAuthController(
    @Value("\${google.client-id}") private val clientId: String,
    @Value("\${google.client-secret}") private val clientSecret: String,
    @Value("\${google.admin-redirect-uri}") private val adminRedirectUri: String,
    @Value("\${admin-email:}") private val adminEmail: String,
    private val adminIdentityService: AdminIdentityService,
) {
    private val log = LoggerFactory.getLogger(AdminOAuthController::class.java)
    private val restTemplate = RestTemplate()

    @GetMapping("/admin/login")
    fun login(): ResponseEntity<Void> {
        val googleAuthUrl = "https://accounts.google.com/o/oauth2/v2/auth?" +
            "client_id=$clientId&" +
            "redirect_uri=${URLEncoder.encode(adminRedirectUri, "UTF-8")}&" +
            "response_type=code&" +
            "scope=openid%20email%20profile&" +
            "hd=sjsu.edu&" +
            "prompt=select_account"
        return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, googleAuthUrl)
            .build()
    }

    @GetMapping("/admin/callback")
    fun callback(
        @RequestParam("code", required = false) code: String?,
        session: HttpSession,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        val destination = "/admin"

        if (code == null) {
            return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, "$destination?error=no_code")
                .build()
        }

        try {
            val tokenRequest = LinkedMultiValueMap<String, String>().apply {
                add("code", code)
                add("client_id", clientId)
                add("client_secret", clientSecret)
                add("redirect_uri", adminRedirectUri)
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

            val userHeaders = HttpHeaders().apply {
                setBearerAuth(tokenResponse.accessToken)
            }
            val userInfo = restTemplate.exchange(
                "https://www.googleapis.com/oauth2/v2/userinfo",
                HttpMethod.GET,
                HttpEntity<Any>(userHeaders),
                GoogleUserInfo::class.java
            ).body!!

            if (adminEmail.isBlank() || !userInfo.email.equals(adminEmail, ignoreCase = true)) {
                log.warn("[admin-oauth] login rejected for ${userInfo.email} - not the configured admin email")
                return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, "$destination?error=not_admin")
                    .build()
            }

            val sessionToken = adminIdentityService.generateToken(userInfo.email, request.remoteAddr)
            log.info("[admin-oauth] admin login for ${userInfo.email}")

            val nameParam = URLEncoder.encode(userInfo.name, "UTF-8")
            val emailParam = URLEncoder.encode(userInfo.email, "UTF-8")
            val sessionParam = URLEncoder.encode(sessionToken, "UTF-8")

            return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, "$destination?name=$nameParam&email=$emailParam&session_token=$sessionParam")
                .build()
        } catch (e: Exception) {
            log.error("[admin-oauth] OAuth exchange failed: ${e.message}", e)
            return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, "$destination?error=auth_failed")
                .build()
        }
    }
}
