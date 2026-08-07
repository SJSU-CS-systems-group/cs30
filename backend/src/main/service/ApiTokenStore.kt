package com.cs30.server.service

import com.cs30.server.models.LoginSession
import com.cs30.server.repository.LoginSessionRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Backed by login_sessions (not an in-memory map) — token is the table's primary key, so this is
 * a direct persistent session store, not a cache in front of one. Every login inserts a new row;
 * old rows are kept (loggedOutAt marks when they ended) as a full history, not just current state.
 */
@Component
class ApiTokenStore(
    private val eventPublisher: ApplicationEventPublisher,
    private val loginSessionRepository: LoginSessionRepository,
) {
    private val log = LoggerFactory.getLogger(ApiTokenStore::class.java)

    // TTL in milliseconds (2 minutes - gives buffer since heartbeat is every 60s)
    private val sessionTtlMs: Long = 2 * 60 * 1000

    // Tokens currently mid-way through endSession(). Guards against the same session being ended
    // concurrently from more than one path at once (e.g. a browser event firing the logout beacon
    // twice, or a heartbeat racing the background sweep for the same expired session).
    private val endingSessions = ConcurrentHashMap.newKeySet<String>()

    fun hasActiveSession(email: String): Boolean {
        val session = loginSessionRepository.findFirstByStudentEmailAndLoggedOutAtIsNull(email) ?: return false
        // Also check TTL - session might be expired but loggedOutAt not yet set
        return !isExpired(session)
    }

    /** Required, not best-effort: resolution is a DB read by token, so a token whose row never got saved could never resolve to anything anyway. */
    fun generate(email: String, platform: String, ipAddress: String): String {
        val token = UUID.randomUUID().toString()
        loginSessionRepository.save(
            LoginSession(
                token = token,
                studentEmail = email,
                ipAddress = ipAddress,
                platform = platform,
            )
        )
        // Never logs the token itself - just who/where/how, same as the other identity services.
        log.info("[ApiTokenStore] session created for {} ({}, {})", email, platform, ipAddress)
        return token
    }

    /** The full row for this token, or null if absent, logged out, or expired. Exposed (not just resolve()/platformFor()) so callers needing more than one field — e.g. the IP-mismatch check — don't have to look the same row up twice. */
    fun activeSession(token: String): LoginSession? {
        val session = loginSessionRepository.findById(token).orElse(null) ?: return null
        if (session.loggedOutAt != null || isExpired(session)) return null
        return session
    }

    fun resolve(token: String): String? = activeSession(token)?.studentEmail

    /** Platform ("web"/"desktop") recorded when this token was issued — for the activity-log CSV column. */
    fun platformFor(token: String): String? = activeSession(token)?.platform

    /** Explicit logout — the student or client deliberately ended the session. Looked up by token (the primary key), not email — the caller always already has the token. */
    fun revokeByToken(token: String) {
        loginSessionRepository.findById(token).orElse(null)
            ?.let { endSession(it, "explicit logout") }
    }

    /**
     * Refresh the session TTL - called on heartbeat. Looked up by token (the primary key), not
     * email — the heartbeat call always carries the token. If the session had already expired,
     * this is the heartbeat-driven half of implicit (TTL) logout — ends it via the same
     * path as explicit logout instead of updating it inline.
     */
    fun refreshSession(token: String): Boolean {
        val session = loginSessionRepository.findById(token).orElse(null) ?: return false
        if (session.loggedOutAt != null) return false
        if (isExpired(session)) {
            // Use lastHeartbeatAt + TTL as the accurate logout time
            val actualExpiry = session.lastHeartbeatAt.plus(sessionTtlMs, ChronoUnit.MILLIS)
            endSession(session, "TTL expired (heartbeat)", actualExpiry)
            return false
        }
        loginSessionRepository.save(session.copy(lastHeartbeatAt = LocalDateTime.now(ZoneOffset.UTC)))
        return true
    }

    private fun isExpired(session: LoginSession): Boolean =
        ChronoUnit.MILLIS.between(session.lastHeartbeatAt, LocalDateTime.now(ZoneOffset.UTC)) > sessionTtlMs

    /**
     * Cleanup expired sessions every minute - the background half of implicit (TTL) logout,
     * for sessions whose client already went away and stopped heartbeating entirely. Each session
     * is isolated with runCatching so one blocked/failed logout doesn't stop the rest of the
     * sweep — a blocked session simply stays active and gets retried next minute.
     */
    @Scheduled(fixedRate = 60000)
    fun cleanupExpiredSessions() {
        val cutoff = LocalDateTime.now(ZoneOffset.UTC).minus(sessionTtlMs, ChronoUnit.MILLIS)
        val expired = loginSessionRepository.findByLoggedOutAtIsNullAndLastHeartbeatAtBefore(cutoff)

        expired.forEach { session ->
            // Use lastHeartbeatAt + TTL as the accurate logout time
            val actualExpiry = session.lastHeartbeatAt.plus(sessionTtlMs, ChronoUnit.MILLIS)
            runCatching { endSession(session, "TTL expired (background sweep)", actualExpiry) }
                .onFailure { log.error("[ApiTokenStore] logout blocked for {} during background sweep: {}", session.studentEmail, it.message) }
        }
    }

    /**
     * The single place a session actually ends, regardless of why. Explicit logout and both
     * implicit (TTL) paths above all route through this, so there's exactly one place to change
     * if session-end ever needs to do more than stamp loggedOutAt.
     *
     * Idempotent: if this session already ended, or another caller is concurrently ending it right
     * now, this is a no-op. The `loggedOutAt` check alone isn't enough to prevent duplicate work —
     * the hook below does a real git commit, so multiple concurrent callers can all see
     * `loggedOutAt == null` before any of them has had a chance to persist it. `endingSessions.add()`
     * is atomic, so only the first concurrent caller for a token proceeds; the rest return
     * immediately without touching the hook or git at all.
     *
     * Publishes [LogoutEvent] *before* persisting loggedOutAt — synchronously, so a listener that
     * throws (e.g. the activity-log commit failing) propagates out of this call and the session
     * is left active. Logout is blocked, not just observed. `try/finally` (not `try/catch`)
     * preserves that: the exception still propagates to the caller, and `finally` only releases the
     * claim so a later, non-concurrent retry can still succeed.
     *
     * @param logoutTime Optional timestamp to use as the logout time. If null, uses current time.
     */
    private fun endSession(session: LoginSession, reason: String, logoutTime: LocalDateTime? = null) {
        if (session.loggedOutAt != null) return
        if (!endingSessions.add(session.token)) return
        try {
            eventPublisher.publishEvent(LogoutEvent(session, reason))
            loginSessionRepository.save(session.copy(loggedOutAt = logoutTime ?: LocalDateTime.now(ZoneOffset.UTC)))
            log.info("[ApiTokenStore] session ended for {} ({})", session.studentEmail, reason)
        } finally {
            endingSessions.remove(session.token)
        }
    }
}
