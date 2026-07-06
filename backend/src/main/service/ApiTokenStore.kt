package com.cs30.server.service

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class SessionInfo(
    val token: String,
    val platform: String = "unknown",
    var lastSeen: Long = System.currentTimeMillis()
)

@Component
class ApiTokenStore {
    private val emailToSession = ConcurrentHashMap<String, SessionInfo>()

    // TTL in milliseconds (2 minutes - gives buffer since heartbeat is every 60s)
    private val sessionTtlMs: Long = 2 * 60 * 1000

    fun hasActiveSession(email: String): Boolean {
        val session = emailToSession[email] ?: return false
        return !isExpired(session)
    }

    fun generate(email: String, platform: String): String {
        val token = UUID.randomUUID().toString()
        emailToSession[email] = SessionInfo(token, platform)
        return token
    }

    fun resolve(token: String): String? {
        return emailToSession.entries
            .find { it.value.token == token && !isExpired(it.value) }
            ?.key
    }

    /** Platform ("web"/"desktop") recorded when this token was issued — for the activity-log CSV column. */
    fun platformFor(token: String): String? {
        return emailToSession.values
            .find { it.token == token && !isExpired(it) }
            ?.platform
    }

    fun revokeByEmail(email: String) {
        emailToSession.remove(email)
    }

    /**
     * Refresh the session TTL - called on heartbeat
     */
    fun refreshSession(email: String): Boolean {
        val session = emailToSession[email] ?: return false
        if (isExpired(session)) {
            emailToSession.remove(email)
            return false
        }
        session.lastSeen = System.currentTimeMillis()
        return true
    }

    private fun isExpired(session: SessionInfo): Boolean {
        return System.currentTimeMillis() - session.lastSeen > sessionTtlMs
    }

    /**
     * Cleanup expired sessions every minute
     */
    @Scheduled(fixedRate = 60000)
    fun cleanupExpiredSessions() {
        val now = System.currentTimeMillis()
        val expiredEmails = emailToSession.entries
            .filter { now - it.value.lastSeen > sessionTtlMs }
            .map { it.key }

        expiredEmails.forEach { email ->
            println("[ApiTokenStore] Session expired for $email (no heartbeat)")
            emailToSession.remove(email)
        }
    }
}