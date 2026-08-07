package com.cs30.server.service

import com.cs30.server.models.AdminSession
import com.cs30.server.repository.AdminSessionRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Identity/session for the admin webpage - same heartbeat-based timeout mechanism as
 * TaIdentityService (the dashboard heartbeats periodically to keep the session alive, rather than
 * the session just expiring on a fixed TTL from login), but a tighter TTL/cadence: the admin page
 * gates the CLI admin token, so a stale session lying around is a bigger deal than a stale TA one.
 */
@Component
class AdminIdentityService(
    private val adminSessionRepository: AdminSessionRepository,
) {
    private val log = LoggerFactory.getLogger(AdminIdentityService::class.java)

    // TTL in milliseconds (10 minutes) - the admin dashboard heartbeats every 2 minutes via
    // /api/admin/check-session, so a merely slow/backgrounded tab still gets 5 heartbeats of
    // buffer before being treated as gone.
    private val sessionTtlMs: Long = 10 * 60 * 1000

    fun generateToken(email: String, ipAddress: String): String {
        val token = UUID.randomUUID().toString()
        val saved = adminSessionRepository.save(AdminSession(token = token, email = email, ipAddress = ipAddress))
        log.info("[admin-identity] new session for {} from {}", email, ipAddress)
        return saved.token
    }

    /**
     * Resolves the session's email from the Authorization header. Does not refresh the TTL - only
     * the dedicated heartbeat call (refreshSession) does that - so a session still expires on
     * schedule even if the dashboard is only hitting other endpoints.
     */
    fun resolve(authorizationHeader: String?): String? {
        val token = extractToken(authorizationHeader) ?: return null
        val session = adminSessionRepository.findById(token).orElse(null) ?: return null
        if (isExpired(session)) {
            adminSessionRepository.deleteById(token)
            return null
        }
        return session.email
    }

    /**
     * Refresh TTL on heartbeat - called from /api/admin/check-session. Returns the session's email
     * if it's still active, or null if the token is missing or was inactive past the TTL (in which
     * case the session is deleted here, same end state as an explicit logout).
     */
    fun refreshSession(token: String): String? {
        val session = adminSessionRepository.findById(token).orElse(null) ?: return null
        if (isExpired(session)) {
            adminSessionRepository.deleteById(token)
            log.info("[admin-identity] session expired for {} (inactive > 10 min)", session.email)
            return null
        }
        adminSessionRepository.save(session.copy(lastHeartbeatAt = LocalDateTime.now(ZoneOffset.UTC)))
        return session.email
    }

    fun revokeToken(token: String) {
        adminSessionRepository.deleteById(token)
    }

    fun token(authorizationHeader: String?): String = extractToken(authorizationHeader).orEmpty()

    private fun isExpired(session: AdminSession): Boolean =
        ChronoUnit.MILLIS.between(session.lastHeartbeatAt, LocalDateTime.now(ZoneOffset.UTC)) > sessionTtlMs

    /**
     * Background sweep for sessions whose tab closed (or lost network) before ever sending a final
     * heartbeat - the browser-side heartbeat alone can't catch that case. Mirrors
     * TaIdentityService.cleanupExpiredSessions.
     */
    @Scheduled(fixedRate = 60000)
    fun cleanupExpiredSessions() {
        val cutoff = LocalDateTime.now(ZoneOffset.UTC).minus(sessionTtlMs, ChronoUnit.MILLIS)
        adminSessionRepository.findByLastHeartbeatAtBefore(cutoff).forEach { session ->
            runCatching {
                adminSessionRepository.deleteById(session.token)
                log.info("[admin-identity] session expired for {} (background sweep)", session.email)
            }.onFailure { log.error("[admin-identity] cleanup failed for {}: {}", session.email, it.message) }
        }
    }

    private fun extractToken(authorizationHeader: String?): String? =
        authorizationHeader
            ?.takeIf { it.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")
            ?.trim()
}
