package com.cs30.server.service

import com.cs30.server.models.TaSession
import com.cs30.server.repository.CourseRepository
import com.cs30.server.repository.TaSessionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Identity resolution and session management for TA tokens.
 * Uses separate ta_sessions table - simpler than student sessions since TAs don't need
 * single-session enforcement, heartbeat/TTL tracking, or logout tracking.
 */
@Component
class TaIdentityService(
    private val taSessionRepository: TaSessionRepository,
    private val courseRepository: CourseRepository,
) {
    private val log = LoggerFactory.getLogger(TaIdentityService::class.java)

    /**
     * Resolves a TA's email from the Authorization header.
     */
    fun resolve(authorizationHeader: String?): String? {
        val token = extractToken(authorizationHeader) ?: return null
        val session = taSessionRepository.findById(token).orElse(null) ?: return null
        return session.email
    }

    /** The auth token identifying the login session. */
    fun token(authorizationHeader: String?): String =
        extractToken(authorizationHeader).orEmpty()

    /** Generate a new TA session token. */
    fun generateToken(email: String, ipAddress: String): String {
        val token = UUID.randomUUID().toString()
        taSessionRepository.save(TaSession(token = token, email = email, ipAddress = ipAddress))
        log.info("[ta-identity] new session for $email from $ipAddress")
        return token
    }

    /** Revoke a TA session token. */
    fun revokeToken(token: String) {
        taSessionRepository.deleteById(token)
    }

    /** Get courses where this email is the TA. */
    fun getCoursesForTa(email: String) = courseRepository.findByTaEmail(email)

    private fun extractToken(authorizationHeader: String?): String? =
        authorizationHeader
            ?.takeIf { it.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")
            ?.trim()
}
