package com.cs30.server.service

import com.cs30.server.models.TaSession
import com.cs30.server.repository.CourseRepository
import com.cs30.server.repository.TaSessionRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Identity resolution and session management for TA tokens.
 * Uses separate ta_sessions table - simpler than student sessions since TAs don't need
 * single-session enforcement or logout tracking (revoke just deletes the row).
 */
@Component
class TaIdentityService(
    private val taSessionRepository: TaSessionRepository,
    private val courseRepository: CourseRepository,
) {
    private val log = LoggerFactory.getLogger(TaIdentityService::class.java)

    // TTL in milliseconds (30 minutes) - the TA dashboard heartbeats every 5 minutes via
    // /api/ta/check-session, so this gives ample buffer before a merely slow/backgrounded tab
    // gets treated as gone.
    private val sessionTtlMs: Long = 30 * 60 * 1000

    /**
     * Resolves a TA's email from the Authorization header. Does not refresh the TTL - only the
     * dedicated heartbeat call (refreshSession) does that - so a session still expires on schedule
     * even if the dashboard is only hitting other endpoints.
     */
    fun resolve(authorizationHeader: String?): String? {
        val token = extractToken(authorizationHeader) ?: return null
        val session = taSessionRepository.findById(token).orElse(null) ?: return null
        if (isExpired(session)) {
            taSessionRepository.deleteById(token)
            return null
        }
        return session.email
    }

    /**
     * Refresh TTL on heartbeat - called from /api/ta/check-session. Returns the session's email if
     * it's still active, or null if the token is missing or was inactive past the TTL (in which
     * case the session is deleted here, same end state as an explicit logout).
     */
    fun refreshSession(token: String): String? {
        val session = taSessionRepository.findById(token).orElse(null) ?: return null
        if (isExpired(session)) {
            taSessionRepository.deleteById(token)
            log.info("[ta-identity] session expired for {} (inactive > 30 min)", session.email)
            return null
        }
        taSessionRepository.save(session.copy(lastHeartbeatAt = LocalDateTime.now(ZoneOffset.UTC)))
        return session.email
    }

    private fun isExpired(session: TaSession): Boolean =
        ChronoUnit.MILLIS.between(session.lastHeartbeatAt, LocalDateTime.now(ZoneOffset.UTC)) > sessionTtlMs

    /**
     * Background sweep for sessions whose tab closed (or lost network) before ever sending a final
     * heartbeat - the browser-side heartbeat alone can't catch that case.
     */
    @Scheduled(fixedRate = 60000)
    fun cleanupExpiredSessions() {
        val cutoff = LocalDateTime.now(ZoneOffset.UTC).minus(sessionTtlMs, ChronoUnit.MILLIS)
        taSessionRepository.findByLastHeartbeatAtBefore(cutoff).forEach { session ->
            runCatching {
                taSessionRepository.deleteById(session.token)
                log.info("[ta-identity] session expired for {} (background sweep)", session.email)
            }.onFailure { log.error("[ta-identity] cleanup failed for {}: {}", session.email, it.message) }
        }
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
