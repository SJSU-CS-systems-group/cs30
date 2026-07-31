package com.cs30.server.service

import com.cs30.server.models.AdminSession
import com.cs30.server.repository.AdminSessionRepository
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Identity/session for the admin webpage - see AdminSession for why it has no heartbeat. */
@Component
class AdminIdentityService(
    private val adminSessionRepository: AdminSessionRepository,
) {
    private val sessionTtlMs: Long = 30 * 60 * 1000

    fun generateToken(email: String, ipAddress: String): String {
        val token = UUID.randomUUID().toString()
        adminSessionRepository.save(AdminSession(token = token, email = email, ipAddress = ipAddress))
        return token
    }

    /** Resolves the session's email from the Authorization header, or null if missing/expired. */
    fun resolve(authorizationHeader: String?): String? {
        val token = extractToken(authorizationHeader) ?: return null
        val session = adminSessionRepository.findById(token).orElse(null) ?: return null
        if (isExpired(session)) {
            adminSessionRepository.deleteById(token)
            return null
        }
        return session.email
    }

    fun revokeToken(token: String) {
        adminSessionRepository.deleteById(token)
    }

    fun token(authorizationHeader: String?): String = extractToken(authorizationHeader).orEmpty()

    private fun isExpired(session: AdminSession): Boolean =
        ChronoUnit.MILLIS.between(session.loggedInAt, LocalDateTime.now(ZoneOffset.UTC)) > sessionTtlMs

    private fun extractToken(authorizationHeader: String?): String? =
        authorizationHeader
            ?.takeIf { it.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")
            ?.trim()
}
