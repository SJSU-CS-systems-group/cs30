package com.cs30.server.controller

import com.cs30.server.models.GoogleTokenResponse
import com.cs30.server.models.GoogleUserInfo
import com.cs30.server.service.AdminIdentityService
import com.cs30.server.service.CliTokenService
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
 * - The CLI's admin token is only ever included in that redirect when just (re)generated - past the
 *   first login, only its hash is stored, so there's nothing to show; the dashboard offers a
 *   reset button instead of an error page for that case
 * - /admin/login?reset=true carries a reset request across the Google round-trip via the
 *   HttpSession (same trick TaOAuthController uses for ta_login_flow), so the callback knows to
 *   invalidate the existing CLI token and mint a fresh one
 */
@RestController
class AdminOAuthController(
    @Value("\${google.client-id}") private val clientId: String,
    @Value("\${google.client-secret}") private val clientSecret: String,
    @Value("\${google.admin-redirect-uri:\${google.redirect-uri:http://localhost:8080/callback}}") private val baseRedirectUri: String,
    @Value("\${admin-email:}") private val adminEmail: String,
    private val cliTokenService: CliTokenService,
    private val adminIdentityService: AdminIdentityService,
) {
    private val log = LoggerFactory.getLogger(AdminOAuthController::class.java)
    private val restTemplate = RestTemplate()

    // Use /admin/callback for admin OAuth
    private val adminRedirectUri: String
        get() = baseRedirectUri.replace("/callback", "/admin/callback")

    @GetMapping("/admin/login")
    fun login(
        @RequestParam("reset", required = false) reset: Boolean?,
        session: HttpSession,
    ): ResponseEntity<Void> {
        if (reset == true) {
            session.setAttribute("admin_reset_requested", true)
        }

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
        val resetRequested = session.getAttribute("admin_reset_requested") == true
        session.removeAttribute("admin_reset_requested")

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

            val result = if (resetRequested) {
                log.info("[admin-oauth] admin token reset for ${userInfo.email}")
                cliTokenService.resetAdminToken(userInfo.email)
            } else {
                cliTokenService.getOrCreateAdminToken(userInfo.email)
            }
            val sessionToken = adminIdentityService.generateToken(userInfo.email, request.remoteAddr)
            log.info("[admin-oauth] admin login for ${userInfo.email}")

            val nameParam = URLEncoder.encode(userInfo.name, "UTF-8")
            val emailParam = URLEncoder.encode(userInfo.email, "UTF-8")
            val sessionParam = URLEncoder.encode(sessionToken, "UTF-8")

            // rawToken is only present the first time this token is ever generated - past that,
            // only its hash is stored, so there's nothing left to show. Either way the admin lands
            // in the dashboard now; the dashboard itself offers a reset button when token is absent.
            val tokenPart = result.rawToken?.let { "&token=${URLEncoder.encode(it, "UTF-8")}" }.orEmpty()

            return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, "$destination?name=$nameParam&email=$emailParam&session_token=$sessionParam$tokenPart")
                .build()
        } catch (e: Exception) {
            log.error("[admin-oauth] OAuth exchange failed: ${e.message}", e)
            return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, "$destination?error=auth_failed")
                .build()
        }
    }
}
