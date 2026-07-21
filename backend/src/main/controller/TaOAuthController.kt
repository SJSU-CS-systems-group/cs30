package com.cs30.server.controller

import com.cs30.server.dto.TaCourseInfo
import com.cs30.server.dto.TaCheckSessionResponse
import com.cs30.server.models.GoogleTokenResponse
import com.cs30.server.models.GoogleUserInfo
import com.cs30.server.repository.CourseRepository
import com.cs30.server.service.TaIdentityService
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
 * OAuth controller for TA authentication.
 * Similar to OAuthController but:
 * - Uses /ta/login and /ta/callback endpoints
 * - Verifies email against taEmail field in Course (not student enrollment)
 * - No single-session restriction (TAs may use multiple devices)
 * - Uses platform "ta-web" to distinguish TA sessions
 */
@RestController
class TaOAuthController(
    @Value("\${google.client-id}") private val clientId: String,
    @Value("\${google.client-secret}") private val clientSecret: String,
    @Value("\${google.ta-redirect-uri:\${google.redirect-uri:http://localhost:8080/callback}}") private val baseRedirectUri: String,
    private val taIdentityService: TaIdentityService,
    private val courseRepository: CourseRepository,
) {
    private val log = LoggerFactory.getLogger(TaOAuthController::class.java)
    private val restTemplate = RestTemplate()

    // Use /ta/callback for TA OAuth
    private val taRedirectUri: String
        get() = baseRedirectUri.replace("/callback", "/ta/callback")

    /**
     * A concurrent request on the same browser session (e.g. the student-side /api/web-logout
     * beacon firing on tab close) can invalidate this HttpSession out from under us mid-callback —
     * any further method call on an invalidated session throws IllegalStateException. The
     * ta_login_flow marker is best-effort bookkeeping, not required for correctness, so a session
     * that's already gone is just as good as one where the attribute was removed.
     */
    private fun HttpSession.safeRemoveAttribute(name: String) {
        try {
            removeAttribute(name)
        } catch (e: IllegalStateException) {
            log.warn("[ta-oauth] session already invalidated while removing '{}': {}", name, e.message)
        }
    }

    @GetMapping("/ta/login")
    fun login(session: HttpSession): ResponseEntity<Void> {
        // Mark this as a TA login flow
        session.setAttribute("ta_login_flow", true)

        val googleAuthUrl = "https://accounts.google.com/o/oauth2/v2/auth?" +
            "client_id=$clientId&" +
            "redirect_uri=${URLEncoder.encode(taRedirectUri, "UTF-8")}&" +
            "response_type=code&" +
            "scope=openid%20email%20profile&" +
            "hd=sjsu.edu&" +
            "prompt=select_account"
        return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, googleAuthUrl)
            .build()
    }

    @GetMapping("/ta/callback")
    fun callback(
        @RequestParam("code", required = false) code: String?,
        session: HttpSession,
        request: HttpServletRequest
    ): ResponseEntity<Void> {
        val destination = "/ta"

        if (code == null) {
            return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, "$destination?error=no_code")
                .build()
        }

        try {
            // Exchange code for token
            val tokenRequest = LinkedMultiValueMap<String, String>().apply {
                add("code", code)
                add("client_id", clientId)
                add("client_secret", clientSecret)
                add("redirect_uri", taRedirectUri)
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

            // Check if user is a TA for any course
            val taCourses = courseRepository.findByTaEmail(userInfo.email)
            if (taCourses.isEmpty()) {
                log.warn("[ta-oauth] login rejected for ${userInfo.email} - not a TA for any course")
                session.safeRemoveAttribute("ta_login_flow")
                return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, "$destination?error=not_ta")
                    .build()
            }

            log.info("[ta-oauth] TA login for ${userInfo.email}, courses: ${taCourses.map { "${it.code}-${it.section}" }}")

            // Generate TA token (stored in separate ta_sessions table)
            val apiToken = taIdentityService.generateToken(userInfo.email, request.remoteAddr)

            session.safeRemoveAttribute("ta_login_flow")

            val nameParam = URLEncoder.encode(userInfo.name, "UTF-8")
            val emailParam = URLEncoder.encode(userInfo.email, "UTF-8")
            val tokenParam = URLEncoder.encode(apiToken, "UTF-8")

            return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, "$destination?name=$nameParam&email=$emailParam&api_token=$tokenParam")
                .build()
        } catch (e: Exception) {
            log.error("[ta-oauth] OAuth exchange failed: ${e.message}", e)
            session.safeRemoveAttribute("ta_login_flow")
            return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, "$destination?error=auth_failed")
                .build()
        }
    }

    @PostMapping("/api/ta/logout")
    fun logout(@RequestHeader("Authorization") authHeader: String?): ResponseEntity<Void> {
        val token = taIdentityService.token(authHeader)
        if (token.isNotBlank()) {
            taIdentityService.revokeToken(token)
        }
        return ResponseEntity.ok().build()
    }

    @GetMapping("/api/ta/check-session")
    fun checkSession(@RequestHeader("Authorization", required = false) authHeader: String?): ResponseEntity<TaCheckSessionResponse> {
        val email = taIdentityService.resolve(authHeader)
        val hasActiveSession = email != null

        val courses = if (email != null) {
            taIdentityService.getCoursesForTa(email).map {
                TaCourseInfo(courseId = it.id, code = it.code, section = it.section)
            }
        } else {
            emptyList()
        }

        return ResponseEntity.ok(TaCheckSessionResponse(
            hasActiveSession = hasActiveSession,
            email = email,
            courses = courses,
        ))
    }
}
